package tsunagu.novel

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import kotlin.test.Test
import kotlin.test.assertEquals

class WebGlueTest {

    private fun eval(script: String): String {
        val c = Context.newBuilder("js", "regex")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { false }
            .build()
        c.use {
            c.getBindings("js").putMember("__hostSleep", NovelJsBridge.hostSleep)
            c.eval("js", NovelJsBridge.WEB_GLUE)
            return c.eval("js", script).asString()
        }
    }

    @Test
    fun resolvesRelativeUrls() {
        val out = eval(
            """
            var b = 'https://novelfire.net/book/shadow-slave/chapters?page=2';
            [
              new URL('/book/x', b).href,
              new URL('chapter-1', b).href,
              new URL('../img/a.jpg', b).href,
              new URL('//cdn.site/a.png', b).href,
              new URL('?page=3', b).href,
              new URL('https://Other.com:443/a/./b/../c?q=1#h').href,
              new URL('/book/shadow-slave', 'https://novelfire.net/').pathname.substring(1),
              new URL('https://a.com:8080/p').origin,
            ].join('\n');
            """,
        )
        assertEquals(
            listOf(
                "https://novelfire.net/book/x",
                "https://novelfire.net/book/shadow-slave/chapter-1",
                "https://novelfire.net/book/img/a.jpg",
                "https://cdn.site/a.png",
                "https://novelfire.net/book/shadow-slave/chapters?page=3",
                "https://other.com/a/c?q=1#h",
                "book/shadow-slave",
                "https://a.com:8080",
            ).joinToString("\n"),
            out,
        )
    }

    @Test
    fun searchParamsStayInSyncWithHref() {
        val out = eval(
            """
            var u = new URL('https://x.com/search?keyword=a');
            u.searchParams.set('keyword', 'shadow slave');
            u.searchParams.append('page', 2);
            var p = new URLSearchParams('a=1&b=2&a=3');
            [u.href, u.searchParams.get('page'), p.getAll('a').join(','), String(p.has('c'))].join('|');
            """,
        )
        assertEquals("https://x.com/search?keyword=shadow+slave&page=2|2|1,3|false", out)
    }

    @Test
    fun headersAndTextCodec() {
        val out = eval(
            """
            var h = new Headers({ 'Content-Type': 'text/html' });
            h.set('referer', 'https://x.com');
            var bytes = new TextEncoder().encode('héllo ✓');
            [h.get('content-type'), Object.keys(h).join(','), new TextDecoder().decode(bytes)].join('|');
            """,
        )
        assertEquals("text/html|Content-Type,referer|héllo ✓", out)
    }

    @Test
    fun timersRunInDueOrderAndClearTimeoutCancels() {
        val out = eval(
            """
            var log = [];
            setTimeout(function () { log.push('b'); }, 20);
            var t = setTimeout(function () { log.push('x'); }, 5);
            setTimeout(function (v) { log.push(v); }, 0, 'a');
            clearTimeout(t);
            while (__runNextTimer()) {}
            log.join('');
            """,
        )
        assertEquals("ab", out)
    }
}
