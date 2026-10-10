package tsunagu.novel

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class AesShimTest {

    private fun eval(script: String): String {
        val c = Context.newBuilder("js", "regex")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { false }
            .build()
        c.use {
            val bindings = c.getBindings("js")
            bindings.putMember("__hostRequire", NovelJsBridge.requireFn("test", c.eval("js", "(function(v){return Promise.resolve(v);})")))
            bindings.putMember("__hostSleep", NovelJsBridge.hostSleep)
            c.eval("js", NovelJsBridge.WEB_GLUE)
            c.eval("js", NovelJsBridge.REQUIRE_GLUE)
            return c.eval("js", script).asString()
        }
    }

    @Test
    fun decryptsWtrLabPayload() {
        val key = "0123456789abcdef0123456789abcdefTRAILING"
        val iv = ByteArray(12) { (it * 7).toByte() }
        val plain = """["<p>Chương 1 — héllo</p>","second"]"""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.take(32).toByteArray(), "AES"), GCMParameterSpec(128, iv))
        val out = c.doFinal(plain.toByteArray())
        val ct = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        val b64 = Base64.getEncoder()
        val payload = "arr:" + listOf(iv, tag, ct).joinToString(":") { b64.encodeToString(it) }

        // WTR-LAB's decrypt(), de-minified.
        val result = eval(
            """
            var aes = __require('@libs/aes');
            var e = '$payload', t = '$key';
            var isArr = e.startsWith('arr:'), r = e.substring(4);
            var parts = r.split(':').map(function (p) { return Uint8Array.from(atob(p), function (ch) { return ch.charCodeAt(0); }); });
            var iv = parts[0], tag = parts[1], ct = parts[2];
            var buf = new Uint8Array(ct.length + tag.length); buf.set(ct); buf.set(tag, ct.length);
            var k = new TextEncoder().encode(t.slice(0, 32));
            var text = new TextDecoder().decode(aes.gcm(k, iv).decrypt(buf));
            var parsed = isArr ? JSON.parse(text) : text;
            parsed[0] + '|' + parsed.length;
            """,
        )
        assertEquals("<p>Chương 1 — héllo</p>|2", result)
    }

    @Test
    fun roundTripsCbcAndCtr() {
        val out = eval(
            """
            var aes = __require('@libs/aes');
            var key = new TextEncoder().encode('k'.repeat(16)), iv = new Uint8Array(16);
            var msg = new TextEncoder().encode('hello cipher world');
            var cbc = aes.cbc(key, iv), ctr = aes.ctr(key, iv);
            var enc = cbc.encrypt(msg);
            [enc.length, new TextDecoder().decode(cbc.decrypt(enc)), new TextDecoder().decode(ctr.decrypt(ctr.encrypt(msg))), btoa(atob('aGk='))].join('|');
            """,
        )
        assertEquals("32|hello cipher world|hello cipher world|aGk=", out)
    }
}
