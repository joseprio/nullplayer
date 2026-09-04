package com.nullplayer.web

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nullplayer.data.Track
import com.nullplayer.data.VaultCrypto
import com.nullplayer.data.VaultFiles
import com.nullplayer.data.VaultRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.CipherInputStream

private const val TAG = "VaultWebServer"

/** Enough to reach the `ftyp` box of an MP4, which is the deepest marker looked for. */
private const val SNIFF_BYTES = 16


/** Characters no filesystem, and no `Content-Disposition` header, should have to carry. */
private fun sanitised(raw: String): String =
    raw.map { if (it.isISOControl() || it in FORBIDDEN) '_' else it }.joinToString("")

private const val FORBIDDEN = "\\/:*?\"<>|"

/** What a downloaded track should be called, and what the browser should be told it is. */
private enum class AudioKind(val mime: String, val extension: String) {
    MP3("audio/mpeg", "mp3"),
    FLAC("audio/flac", "flac"),
    OGG("audio/ogg", "ogg"),
    WAV("audio/wav", "wav"),
    MP4("audio/mp4", "m4a"),

    /** Playable by whatever put it in, but not something to guess an extension for. */
    UNKNOWN("application/octet-stream", "audio");

    companion object {
        fun of(head: ByteArray, length: Int): AudioKind = when {
            head.matches("fLaC", 0, length) -> FLAC
            head.matches("OggS", 0, length) -> OGG
            head.matches("RIFF", 0, length) && head.matches("WAVE", 8, length) -> WAV
            head.matches("ftyp", 4, length) -> MP4
            head.matches("ID3", 0, length) -> MP3
            // A bare MPEG frame opens with eleven set bits and no tag in front of it.
            length >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> MP3
            else -> UNKNOWN
        }
    }
}

private fun ByteArray.matches(marker: String, offset: Int, length: Int): Boolean =
    offset + marker.length <= length &&
        marker.indices.all { this[offset + it] == marker[it].code.toByte() }

/** Reads until the buffer is full or the stream ends, which a single `read` does not promise. */
private fun InputStream.readAtMost(into: ByteArray): Int {
    var total = 0
    while (total < into.size) {
        val read = read(into, total, into.size - total)
        if (read <= 0) break
        total += read
    }
    return total
}

/**
 * A small HTTP server that turns any browser on the same network into the dock.
 *
 * It is PIN-gated on purpose: the vault exists so that this music is not casually reachable, and a
 * wide-open upload endpoint on a coffee-shop wifi would undo that in one step.
 */
class VaultWebServer(
    private val context: Context,
    private val repository: VaultRepository,
    port: Int,
    /**
     * The group the phone currently has open, or blank for the whole vault. Uploads are filed
     * into it, and it is what the page lists.
     */
    private val activeVaultId: () -> String,
    /** Raised once too many PINs have been guessed. The server is finished at that point. */
    private val onIntrusion: () -> Unit,
) : NanoHTTPD(port) {

    /** Shown on the phone, typed into the browser once. Regenerated every time the server starts. */
    val pin: String = "%06d".format(SecureRandom().nextInt(1_000_000))

    /**
     * Live sessions, each against the moment it was last heard from.
     *
     * A token on its own would only ever say "somebody logged in once" — a browser closed without
     * logging out never sends anything again, and the count would climb and never fall. Holding a
     * timestamp instead lets a session age out, which is what makes "active" mean present rather
     * than merely admitted at some point.
     */
    private val vault = VaultFiles(context)

    private val sessions = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    private val failedAttempts = AtomicInteger(0)

    /** Set the moment the limit is reached, so requests already in flight are refused too. */
    @Volatile private var locked = false

    init {
        // Android's java.io.tmpdir is not writable, so NanoHTTPD's default temp handling fails on
        // any upload. Point it at our own cache directory instead.
        val scratch = File(context.cacheDir, "upload").apply { mkdirs() }
        setTempFileManagerFactory { ScratchTempFileManager(scratch) }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (t: Throwable) {
            Log.w(TAG, "Request failed: ${session.uri}", t)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "${t.message}"))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val authorised = isAuthorised(session)

        return when {
            session.uri == "/login" && session.method == Method.POST -> login(session)

            !authorised -> when (session.uri) {
                "/" -> html(loginPage(incorrect = session.parameters.containsKey("retry")))
                else -> json(
                    Response.Status.UNAUTHORIZED,
                    JSONObject().put("error", "Enter the PIN shown on the phone.")
                )
            }

            session.uri == "/" -> html(asset("manager.html"))
            session.uri == "/api/tracks" -> json(Response.Status.OK, tracksPayload())
            session.uri == "/api/upload" && session.method == Method.POST -> upload(session)
            session.uri == "/api/delete" && session.method == Method.POST -> delete(session)
            session.uri == "/api/download" -> download(session)
            session.uri == "/logout" -> logout(session)
            // The page beats on this so an open-but-idle tab still reads as present.
            session.uri == "/api/ping" -> json(Response.Status.OK, JSONObject().put("ok", true))

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "No such endpoint"))
        }
    }

    // -- Authentication ---------------------------------------------------------------------

    private fun isAuthorised(session: IHTTPSession): Boolean {
        val cookie = session.headers["cookie"] ?: return false
        val token = cookie.split(';')
            .map { it.trim() }
            .filter { it.startsWith("$COOKIE=") }
            .map { it.removePrefix("$COOKIE=") }
            .firstOrNull { sessions.containsKey(it) }
            ?: return false
        // Every authorised request counts as a sign of life, so the heartbeat is a backstop for an
        // idle page rather than the only thing keeping a busy one alive.
        sessions[token] = System.currentTimeMillis()
        return true
    }

    /**
     * How many people are on the page right now.
     *
     * Expired sessions are dropped as they are counted: there is no other sweeper, and a map that
     * only ever grows would leak a token per login for as long as the server is up.
     */
    fun activeUsers(): Int {
        val cutoff = System.currentTimeMillis() - ACTIVE_WINDOW_MS
        sessions.entries.removeAll { it.value < cutoff }
        return sessions.size
    }

    /**
     * Six digits is only a million guesses, which a script on the same LAN would work through
     * given time. Rather than slow that down, [MAX_ATTEMPTS] misses shut the server off and leave
     * it off until the owner turns it back on from the phone.
     *
     * That is what makes the PIN sufficient: a guesser gets five tries, total, and the next PIN is
     * a fresh one because it is regenerated every time the server starts.
     */
    private fun login(session: IHTTPSession): Response {
        if (locked) return redirect("/?retry=1")

        val body = HashMap<String, String>()
        session.parseBody(body)
        val supplied = session.parameters["pin"]?.firstOrNull()?.trim()

        if (supplied != pin) {
            if (failedAttempts.incrementAndGet() >= MAX_ATTEMPTS) {
                locked = true
                Log.w(TAG, "Stopping: $MAX_ATTEMPTS wrong PINs")
                onIntrusion()
            }
            return redirect("/?retry=1")
        }
        failedAttempts.set(0)

        val token = ByteArray(24).let { random.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } }
        sessions[token] = System.currentTimeMillis()
        return redirect("/").apply {
            addHeader("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
        }
    }

    private fun logout(session: IHTTPSession): Response {
        session.headers["cookie"]?.split(';')?.forEach {
            sessions.remove(it.trim().removePrefix("$COOKIE="))
        }
        return redirect("/").apply {
            addHeader("Set-Cookie", "$COOKIE=; Path=/; Max-Age=0")
        }
    }

    // -- Endpoints --------------------------------------------------------------------------

    private fun tracksPayload(): JSONObject {
        val vaultId = activeVaultId()
        val tracks = runBlocking { repository.tracks(vaultId) }
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("tracks", array)
            .put("bytes", runBlocking { repository.vaultBytes(vaultId) })
    }

    private fun upload(session: IHTTPSession): Response {
        // NanoHTTPD spools each part to a temp file and hands back its path.
        val parts = HashMap<String, String>()
        session.parseBody(parts)

        var imported = 0
        val failed = JSONArray()

        for ((field, temporaryPath) in parts) {
            if (!field.startsWith("file")) continue
            val temporary = File(temporaryPath)
            // The browser sends the real filename in a parallel parameter of the same name.
            val originalName = session.parameters[field]?.firstOrNull()

            val result = runBlocking {
                repository.import(
                    source = Uri.fromFile(temporary),
                    groupIds = listOfNotNull(activeVaultId().takeIf { it.isNotEmpty() }),
                    fallbackName = originalName,
                )
            }
            if (result.isSuccess) imported++ else failed.put(originalName ?: field)
            temporary.delete()
        }

        return json(
            Response.Status.OK,
            JSONObject().put("imported", imported).put("failed", failed)
        )
    }

    /**
     * Streams one track back out, decrypted.
     *
     * Decryption happens on the way to the socket rather than into a temporary file: a plaintext
     * copy on disk, however briefly, is the one thing the vault exists to prevent. It is behind
     * the same PIN as everything else here, and the vault's own screens are already the place
     * where tracks have names — but this is the one endpoint that lets audio leave, so it is
     * worth being clear that it does.
     */
    private fun download(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Missing id"))
        val track = runBlocking { repository.track(id) }
            ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Unknown track"))

        val file = vault.fileFor(id)
        if (!file.exists()) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Unknown track"))
        }

        val raw = file.inputStream()
        val iv = ByteArray(VaultCrypto.IV_LENGTH)
        if (raw.readAtMost(iv) != VaultCrypto.IV_LENGTH) {
            raw.close()
            return json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", "Damaged vault entry"),
            )
        }

        val plain = CipherInputStream(raw, VaultCrypto.decryptorAt(iv, 0))

        // Nothing records what a track was encoded as — the player asks the decoder rather than
        // the database — so the container is read back off the front of the stream. Those bytes
        // are then pushed back in front of it, since the browser needs the whole file.
        val head = ByteArray(SNIFF_BYTES)
        val headLength = plain.readAtMost(head)
        val kind = AudioKind.of(head, headLength)
        val body = SequenceInputStream(ByteArrayInputStream(head, 0, headLength), plain)

        return newFixedLengthResponse(
            Response.Status.OK,
            kind.mime,
            body,
            file.length() - VaultCrypto.IV_LENGTH,
        ).apply { addHeader("Content-Disposition", disposition(fileName(track, kind))) }
            .withNoStore()
    }

    private fun delete(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val payload = JSONObject(body["postData"] ?: "{}")

        return when (val id = payload.optString("id").takeIf { it.isNotEmpty() }) {
            null -> json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Missing id"))
            else -> {
                val track = runBlocking { repository.track(id) }
                    ?: return json(
                        Response.Status.NOT_FOUND,
                        JSONObject().put("error", "Unknown track")
                    )
                runBlocking { repository.delete(track) }
                json(Response.Status.OK, JSONObject().put("deleted", id))
            }
        }
    }

    // -- Plumbing ---------------------------------------------------------------------------

    private fun Track.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title ?: JSONObject.NULL)
        .put("artist", artist ?: JSONObject.NULL)
        .put("album", album ?: JSONObject.NULL)
        .put("durationMs", durationMs)
        .put("addedAt", addedAt)

    /**
     * A name a browser can save.
     *
     * Both spellings are sent: `filename*` carries the real characters for anything modern, and
     * the plain `filename` is the ASCII fallback for what does not read it.
     */
    private fun disposition(name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun fileName(track: Track, kind: AudioKind): String {
        val stem = listOfNotNull(
            track.artist?.takeIf { it.isNotBlank() },
            track.title?.takeIf { it.isNotBlank() },
        ).joinToString(" - ").ifBlank { "track" }
        return sanitised(stem.take(80)) + "." + kind.extension
    }

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun html(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
            .withNoStore()

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
            .withNoStore()

    private fun redirect(location: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "").apply {
            addHeader("Location", location)
        }

    /** Nothing served here should ever end up in a browser cache on someone else's machine. */
    private fun Response.withNoStore(): Response = apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun loginPage(incorrect: Boolean): String = """
        <!doctype html>
        <html lang="en"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>nullplayer</title>
        <style>
          :root { color-scheme: dark; }
          body { margin:0; min-height:100vh; display:grid; place-items:center;
                 background:#0b0b0d; color:#e8e8ea;
                 font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; }
          form { width:min(320px,88vw); text-align:center; }
          h1 { font-size:15px; font-weight:600; letter-spacing:.14em;
               text-transform:lowercase; color:#8b8d94; margin:0 0 28px; }
          input { width:100%; box-sizing:border-box; padding:14px; font-size:22px;
                  text-align:center; letter-spacing:.4em; border-radius:10px;
                  border:1px solid #2c2d32; background:#141519; color:#e8e8ea; }
          input:focus { outline:none; border-color:#5b5d66; }
          button { width:100%; margin-top:12px; padding:13px; border:0; border-radius:10px;
                   background:#e8e8ea; color:#0b0b0d; font-size:15px; font-weight:600;
                   cursor:pointer; }
          p { color:#c8563f; font-size:13px; height:18px; margin:14px 0 0; }
        </style></head>
        <body><form method="post" action="/login">
          <h1>nullplayer</h1>
          <input name="pin" inputmode="numeric" autocomplete="off" maxlength="6"
                 placeholder="000000" autofocus>
          <button type="submit">Unlock</button>
          <p>${if (incorrect) "That PIN is not right." else ""}</p>
        </form></body></html>
    """.trimIndent()

    /** Spools multipart uploads into the app's own cache instead of the unwritable system temp. */
    private class ScratchTempFileManager(private val directory: File) : TempFileManager {

        private val open = mutableListOf<TempFile>()

        override fun createTempFile(filenameHint: String?): TempFile =
            ScratchTempFile(directory).also { open.add(it) }

        override fun clear() {
            open.forEach { runCatching { it.delete() } }
            open.clear()
        }
    }

    private class ScratchTempFile(directory: File) : TempFile {

        private val file: File = File.createTempFile("np-", ".part", directory)
        private val stream = FileOutputStream(file)

        override fun open(): OutputStream = stream

        override fun getName(): String = file.absolutePath

        override fun delete() {
            runCatching { stream.close() }
            file.delete()
        }
    }

    companion object {
        private const val COOKIE = "np_session"
        const val MAX_ATTEMPTS = 5

        /** Two missed heartbeats. Long enough to ride out a lock screen or a flaky Wi-Fi moment. */
        private const val ACTIVE_WINDOW_MS = 45_000L
    }
}
