package tsunagu.novel

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import kotlin.test.Test
import kotlin.test.assertEquals

class CheerioShimTest {

    private fun eval(script: String): String {
        val c = Context.newBuilder("js", "regex")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { false }
            .build()
        c.use {
            c.getBindings("js").putMember(
                "__hostRequire",
                NovelJsBridge.requireFn("test", c.eval("js", "(function(v){return Promise.resolve(v);})")),
            )
            c.eval("js", NovelJsBridge.REQUIRE_GLUE)
            return c.eval("js", "var cheerio = __require('cheerio');\n$script").asString()
        }
    }

    private val page = """
        <div class="list">
          <div class="novel-item"><a href="/a" title="Alpha">A</a><div class="novel-cover"><img data-src="/a.jpg"></div></div>
          <div class="novel-item"><h4><a href="/b">Beta</a></h4><div class="novel-cover"><img src="/b.jpg"></div></div>
        </div>
    """.trimIndent().replace("\n", "")

    @Test
    fun toArrayYieldsRawNodesThatRewrap() {
        // Novel Fire's parseNovels shape.
        val out = eval(
            """
            var $ = cheerio.load(${quote(page)});
            $('.novel-item').toArray().map(function (n) {
              var c = $(n);
              var title = c.find('a').attr('title') ?? c.find('h4').text().trim();
              var img = c.find('.novel-cover > img');
              return n.name + ':' + title + ':' + (img.attr('data-src') ?? img.attr('src'));
            }).join('|');
            """,
        )
        assertEquals("div:Alpha:/a.jpg|div:Beta:/b.jpg", out)
    }

    @Test
    fun missingAttrIsNullNotEmpty() {
        assertEquals("null", eval("var $ = cheerio.load('<a href=\"x\"></a>'); String($('a').attr('title'));"))
    }

    @Test
    fun textKeepsNewlinesAndFindExcludesSelf() {
        val out = eval(
            """
            var $ = cheerio.load('<div class="s"><p>one</p><br><p>two</p><div class="s">in</div></div>');
            var c = $('.s').first();
            c.find('br').replaceWith('\n');
            c.find('p').before('\n').after('\n');
            JSON.stringify([c.find('.s').length, c.text()]);
            """,
        )
        assertEquals("[1,\"\\none\\n\\n\\ntwo\\nin\"]", out)
    }

    @Test
    fun removeContentsFilterAndEach() {
        val out = eval(
            """
            var $ = cheerio.load('<div id="c"><p>keep</p><nfx>ad</nfx><script>x()</script>tail</div>');
            var c = $('#c');
            c.find('script').remove();
            c.find(':not(p, div)').each(function (i, el) { if (el.name.substring(0, 2) === 'nf') $(el).remove(); });
            var texts = c.contents().filter(function () { return this.type === 'text'; }).map(function (i, el) { return el.data; }).get();
            c.html() + '|' + texts.join(',');
            """,
        )
        assertEquals("<p>keep</p>tail|tail", out)
    }

    @Test
    fun jqueryFirstLastPseudos() {
        val out = eval(
            """
            var $ = cheerio.load('<div class="h"><span><strong>1</strong></span><span><strong>2</strong></span></div>');
            $('.h span:first strong').text() + $('.h span:last strong').text();
            """,
        )
        assertEquals("12", out)
    }

    @Test
    fun fragmentModeHtml() {
        assertEquals("<p>x</p>", eval("var $ = cheerio.load('<p>x</p>', null, false); $.html();"))
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
