package tsunagu.novel

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.Proxy
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.Collections
import java.util.IdentityHashMap

// Cheerio's API on top of jsoup. LNReader plugins are written against real
// cheerio, so semantics follow cheerio: callbacks and indexing yield raw DOM
// nodes (domhandler shape), missing attrs are null, text() is unnormalized
// textContent, and find() never matches the context node itself.

/** The `$` returned by `cheerio.load`. */
class CheerioRoot(private val root: Element) : ProxyExecutable, ProxyObject {

    override fun execute(vararg args: Value): Any {
        val a = args.getOrNull(0)
        val ctx = args.getOrNull(1)
        if (a == null || a.isNull) return CheerioSelection(emptyList(), this)
        if (a.isString) {
            val s = a.asString()
            if (s.trimStart().startsWith("<")) return CheerioSelection(fragment(s), this)
            val scope: List<Node> = when {
                ctx == null || ctx.isNull -> listOf(root)
                ctx.isString -> selectIn(listOf(root), ctx.asString())
                else -> nodesOf(ctx) ?: emptyList()
            }
            return CheerioSelection(selectIn(scope, s), this)
        }
        return CheerioSelection(nodesOf(a) ?: emptyList(), this)
    }

    fun fragment(html: String): List<Node> {
        val holder = Jsoup.parseBodyFragment(html)
        holder.outputSettings().prettyPrint(false)
        return holder.body().childNodes().toList()
    }

    override fun getMember(key: String): Any? = when (key) {
        "root" -> ProxyExecutable { CheerioSelection(listOf(root), this) }
        "html" -> ProxyExecutable { args ->
            val a = args.getOrNull(0)
            if (a == null || a.isNull) innerOrDoc() else (nodesOf(a) ?: emptyList()).joinToString("") { it.outerHtml() }
        }
        "text" -> ProxyExecutable { args ->
            val a = args.getOrNull(0)
            if (a == null || a.isNull) textContent(root) else (nodesOf(a) ?: emptyList()).joinToString("") { textContent(it) }
        }
        "contains" -> ProxyExecutable { args ->
            val outer = nodesOf(args[0])?.firstOrNull()
            var n = nodesOf(args[1])?.firstOrNull()?.parentNode()
            while (n != null && n !== outer) n = n.parentNode()
            n != null
        }
        else -> null
    }

    // Document mode serializes the whole document; fragment mode (load(html, opts, false)) just the content.
    private fun innerOrDoc(): String = if (root is Document) root.outerHtml() else root.html()

    override fun getMemberKeys(): Any = ProxyArray.fromArray("root", "html", "text", "contains")
    override fun hasMember(key: String): Boolean = key in setOf("root", "html", "text", "contains")
    override fun putMember(key: String, value: Value?) {}

    companion object {
        fun load(args: Array<Value>): CheerioRoot {
            val html = args.getOrNull(0)?.let { if (it.isString) it.asString() else if (it.isNull) "" else it.toString() } ?: ""
            val opts = args.getOrNull(1)
            val xml = opts != null && !opts.isNull && opts.hasMembers() &&
                (truthy(opts.getMember("xmlMode")) || truthy(opts.getMember("xml")))
            val isDocument = args.getOrNull(2)?.let { it.isNull || truthy(it) } ?: true
            val doc = if (xml) Jsoup.parse(html, "", Parser.xmlParser()) else Jsoup.parse(html)
            doc.outputSettings().prettyPrint(false)
            val root: Element = if (!xml && !isDocument) doc.body() else doc
            return CheerioRoot(root)
        }
    }
}

class CheerioSelection(
    val nodes: List<Node>,
    private val root: CheerioRoot,
    private val prev: CheerioSelection? = null,
) : ProxyArray, ProxyObject {

    private val elements: List<Element> get() = nodes.filterIsInstance<Element>()

    private fun make(ns: List<Node>) = CheerioSelection(ns, root, this)

    override fun get(index: Long): Any? = nodes.getOrNull(index.toInt())?.let(::NodeProxy)
    override fun set(index: Long, value: Value?) = throw UnsupportedOperationException("cheerio selection is read-only")
    override fun getSize(): Long = nodes.size.toLong()

    override fun getMember(key: String): Any? = when (key) {
        "length" -> nodes.size.toLong()

        // ---- content ----
        "text" -> fn { a ->
            if (a.isEmpty() || a[0].isNull) nodes.joinToString("") { textContent(it) }
            else { val s = str(a[0]); nodes.forEach { setText(it, s) }; this }
        }
        "html" -> fn { a ->
            if (a.isEmpty() || a[0].isNull) {
                when (val n = nodes.firstOrNull()) {
                    null -> null
                    is Element -> n.html()
                    else -> n.outerHtml()
                }
            } else { val s = str(a[0]); elements.forEach { it.html(s) }; this }
        }
        "toString" -> fn { nodes.joinToString("") { it.outerHtml() } }
        "attr" -> fn { a -> attr(a) }
        "removeAttr" -> fn { a -> val names = str(a[0]).split(Regex("\\s+")).filter { it.isNotEmpty() }; elements.forEach { e -> names.forEach { e.removeAttr(it) } }; this }
        "prop" -> fn { a -> prop(str(a[0])) }
        "data" -> fn { a -> data(a) }
        "val" -> fn { a -> value(a) }
        "css" -> fn { a -> css(a) }
        "hasClass" -> fn { a -> val c = str(a[0]); elements.any { it.hasClass(c) } }
        "addClass" -> fn { a -> classes(a).let { cs -> elements.forEach { e -> cs.forEach { e.addClass(it) } } }; this }
        "removeClass" -> fn { a ->
            if (a.isEmpty() || a[0].isNull) elements.forEach { it.removeAttr("class") }
            else classes(a).let { cs -> elements.forEach { e -> cs.forEach { e.removeClass(it) } } }
            this
        }
        "toggleClass" -> fn { a -> classes(a).let { cs -> elements.forEach { e -> cs.forEach { e.toggleClass(it) } } }; this }

        // ---- traversal ----
        "find" -> fn { a ->
            val q = a.getOrNull(0)
            when {
                q == null || q.isNull -> make(emptyList())
                q.isString -> make(selectIn(nodes, q.asString()))
                else -> {
                    val targets = nodesOf(q) ?: emptyList()
                    make(targets.filter { t -> nodes.any { n -> n !== t && isAncestor(n, t) } })
                }
            }
        }
        "children" -> fn { a -> make(filterBy(uniq(elements.flatMap { it.children() }), a.getOrNull(0))) }
        "contents" -> fn { make(nodes.flatMap { it.childNodes() }) }
        "parent" -> fn { a -> make(filterBy(uniq(nodes.mapNotNull { parentEl(it) }), a.getOrNull(0))) }
        "parents" -> fn { a -> make(filterBy(uniq(nodes.flatMap { ancestors(it) }), a.getOrNull(0))) }
        "parentsUntil" -> fn { a ->
            val stop = matcher(a.getOrNull(0), matchNone = true)
            make(filterBy(uniq(nodes.flatMap { ancestors(it).takeWhile { p -> !stop(0, p) } }), a.getOrNull(1)))
        }
        "closest" -> fn { a ->
            val m = matcher(a.getOrNull(0), matchNone = true)
            make(uniq(nodes.mapNotNull { n -> (listOfNotNull(n as? Element) + ancestors(n)).firstOrNull { m(0, it) } }))
        }
        "next" -> fn { a -> make(filterBy(uniq(nodes.mapNotNull { nextEl(it) }), a.getOrNull(0))) }
        "prev" -> fn { a -> make(filterBy(uniq(nodes.mapNotNull { prevEl(it) }), a.getOrNull(0))) }
        "nextAll" -> fn { a -> make(filterBy(uniq(nodes.flatMap { siblingsAfter(it) }), a.getOrNull(0))) }
        "prevAll" -> fn { a -> make(filterBy(uniq(nodes.flatMap { siblingsBefore(it) }), a.getOrNull(0))) }
        "nextUntil" -> fn { a ->
            val stop = matcher(a.getOrNull(0), matchNone = true)
            make(filterBy(uniq(nodes.flatMap { siblingsAfter(it).takeWhile { s -> !stop(0, s) } }), a.getOrNull(1)))
        }
        "prevUntil" -> fn { a ->
            val stop = matcher(a.getOrNull(0), matchNone = true)
            make(filterBy(uniq(nodes.flatMap { siblingsBefore(it).takeWhile { s -> !stop(0, s) } }), a.getOrNull(1)))
        }
        "siblings" -> fn { a ->
            make(filterBy(uniq(nodes.flatMap { n -> (parentEl(n)?.children() ?: emptyList()).filter { it !== n } }), a.getOrNull(0)))
        }

        // ---- filtering ----
        "filter" -> fn { a -> val m = matcher(a.getOrNull(0)); make(nodes.filterIndexed { i, n -> m(i, n) }) }
        "not" -> fn { a -> val m = matcher(a.getOrNull(0), matchNone = true); make(nodes.filterIndexed { i, n -> !m(i, n) }) }
        "is" -> fn { a -> val m = matcher(a.getOrNull(0), matchNone = true); nodes.withIndex().any { (i, n) -> m(i, n) } }
        "has" -> fn { a ->
            val q = a.getOrNull(0)
            make(elements.filter { e ->
                when {
                    q == null || q.isNull -> false
                    q.isString -> selectIn(listOf(e), q.asString()).isNotEmpty()
                    else -> (nodesOf(q) ?: emptyList()).any { it !== e && isAncestor(e, it) }
                }
            })
        }
        "first" -> fn { make(nodes.take(1)) }
        "last" -> fn { make(nodes.takeLast(1)) }
        "eq" -> fn { a -> make(listOfNotNull(at(a[0].asInt()))) }
        "slice" -> fn { a -> make(slice(a)) }
        "add" -> fn { a ->
            val q = a.getOrNull(0)
            val more = when {
                q == null || q.isNull -> emptyList()
                q.isString -> (root.execute(*a) as CheerioSelection).nodes
                else -> nodesOf(q) ?: emptyList()
            }
            make(uniq(nodes + more))
        }
        "addBack" -> fn { a -> make(uniq(nodes + filterBy(prev?.nodes ?: emptyList(), a.getOrNull(0)))) }
        "end" -> fn { prev ?: CheerioSelection(emptyList(), root) }

        // ---- iteration ----
        "each" -> fn { a ->
            val cb = a[0]
            for ((i, n) in nodes.withIndex()) {
                val p = NodeProxy(n)
                val r = cb.invokeMember("call", p, i.toLong(), p)
                if (r.isBoolean && !r.asBoolean()) break
            }
            this
        }
        "map" -> fn { a ->
            val cb = a[0]
            val out = ArrayList<Any?>()
            for ((i, n) in nodes.withIndex()) {
                val p = NodeProxy(n)
                val r = cb.invokeMember("call", p, i.toLong(), p)
                when {
                    r.isNull -> {}
                    r.hasArrayElements() && !r.isProxyObject -> for (j in 0 until r.arraySize) out.add(r.getArrayElement(j))
                    else -> out.add(r)
                }
            }
            MappedSelection(out, root)
        }
        "get" -> fn { a ->
            if (a.isEmpty() || a[0].isNull) ProxyArray.fromList(nodes.map { NodeProxy(it) })
            else at(a[0].asInt())?.let(::NodeProxy)
        }
        "toArray" -> fn { ProxyArray.fromList(nodes.map { NodeProxy(it) }) }
        "index" -> fn { a -> index(a) }

        // ---- manipulation ----
        "remove" -> fn { a -> filterBy(nodes, a.getOrNull(0)).forEach { if (it.parentNode() != null) it.remove() }; this }
        "empty" -> fn { elements.forEach { it.empty() }; this }
        "append" -> fn { a -> eachTarget(elements, a) { t, c -> c.forEach { t.appendChild(it) } }; this }
        "prepend" -> fn { a -> eachTarget(elements, a) { t, c -> t.insertChildren(0, c) }; this }
        "after" -> fn { a -> eachTarget(nodes, a) { t, c -> parentAny(t)?.insertChildren(t.siblingIndex() + 1, c) }; this }
        "before" -> fn { a -> eachTarget(nodes, a) { t, c -> parentAny(t)?.insertChildren(t.siblingIndex(), c) }; this }
        "appendTo" -> fn { a -> eachTarget(targetsOf(a[0]).filterIsInstance<Element>(), nodes) { t, c -> c.forEach { t.appendChild(it) } }; this }
        "prependTo" -> fn { a -> eachTarget(targetsOf(a[0]).filterIsInstance<Element>(), nodes) { t, c -> t.insertChildren(0, c) }; this }
        "insertAfter" -> fn { a -> eachTarget(targetsOf(a[0]), nodes) { t, c -> parentAny(t)?.insertChildren(t.siblingIndex() + 1, c) }; this }
        "insertBefore" -> fn { a -> eachTarget(targetsOf(a[0]), nodes) { t, c -> parentAny(t)?.insertChildren(t.siblingIndex(), c) }; this }
        "replaceWith" -> fn { a ->
            val content = a.getOrNull(0)
            nodes.forEachIndexed { i, n ->
                val parent = parentAny(n) ?: return@forEachIndexed
                val c = if (content != null && content.canExecute()) {
                    val p = NodeProxy(n)
                    contentNodes(arrayOf(content.invokeMember("call", p, i.toLong(), p)), clone = false)
                } else {
                    contentNodes(a, clone = i < nodes.size - 1)
                }
                parent.insertChildren(n.siblingIndex(), c)
                n.remove()
            }
            this
        }
        "wrap" -> fn { a ->
            val w = a[0]
            val html = if (w.isString) w.asString() else nodesOf(w)?.firstOrNull()?.outerHtml()
            if (html != null) nodes.forEach { if (it.parentNode() != null) it.wrap(html) }
            this
        }
        "unwrap" -> fn { uniq(nodes.mapNotNull { parentEl(it) }).forEach { if (it.tagName() != "body") it.unwrap() }; this }
        "clone" -> fn { CheerioSelection(nodes.map { it.clone() }, root) }

        else -> null
    }

    override fun getMemberKeys(): Any = ProxyArray.fromArray(*KEYS.toTypedArray())
    override fun hasMember(key: String): Boolean = key in KEYS
    override fun putMember(key: String, value: Value?) {}
    override fun removeMember(key: String): Boolean = false

    // ---- helpers bound to this selection ----

    private fun at(i: Int): Node? = nodes.getOrNull(if (i < 0) nodes.size + i else i)

    private fun slice(a: Array<Value>): List<Node> {
        val n = nodes.size
        fun norm(v: Value?, dflt: Int): Int {
            if (v == null || v.isNull) return dflt
            val x = v.asInt()
            return if (x < 0) maxOf(0, n + x) else minOf(x, n)
        }
        val s = norm(a.getOrNull(0), 0)
        val e = norm(a.getOrNull(1), n)
        return if (s < e) nodes.subList(s, e).toList() else emptyList()
    }

    private fun attr(a: Array<Value>): Any? {
        val k = a.getOrNull(0)
        val first = elements.firstOrNull()
        return when {
            k == null || k.isNull -> first?.let { e -> ProxyObject.fromMap(HashMap<String, Any>().also { m -> e.attributes().forEach { m[it.key] = it.value } }) }
            !k.isString && k.hasMembers() -> {
                for (key in k.memberKeys) elements.forEach { it.attr(key, str(k.getMember(key))) }
                this
            }
            a.size >= 2 -> {
                val name = k.asString()
                val v = a[1]
                elements.forEach { if (v.isNull) it.removeAttr(name) else it.attr(name, str(v)) }
                this
            }
            else -> first?.let { if (it.hasAttr(k.asString())) it.attr(k.asString()) else null }
        }
    }

    private fun prop(name: String): Any? {
        val n = nodes.firstOrNull() ?: return null
        val e = n as? Element
        return when (name) {
            "tagName", "nodeName" -> e?.tagName()?.uppercase()
            "outerHTML" -> n.outerHtml()
            "innerHTML" -> e?.html()
            "textContent", "innerText" -> textContent(n)
            "checked", "selected", "disabled" -> e?.hasAttr(name) ?: false
            else -> e?.let { if (it.hasAttr(name)) it.attr(name) else null }
        }
    }

    private fun data(a: Array<Value>): Any? {
        val e = elements.firstOrNull() ?: return null
        val k = a.getOrNull(0)
        if (k == null || k.isNull) {
            return ProxyObject.fromMap(HashMap<String, Any>().also { m -> e.dataset().forEach { (key, v) -> m[camel(key)] = v } })
        }
        val attrName = "data-" + kebab(k.asString())
        if (a.size >= 2) { elements.forEach { it.attr(attrName, str(a[1])) }; return this }
        return if (e.hasAttr(attrName)) e.attr(attrName) else null
    }

    private fun value(a: Array<Value>): Any? {
        if (a.isNotEmpty() && !a[0].isNull) {
            val v = str(a[0])
            elements.forEach { if (it.tagName() == "textarea") it.text(v) else it.attr("value", v) }
            return this
        }
        val e = elements.firstOrNull() ?: return null
        return when (e.tagName()) {
            "textarea" -> e.wholeText()
            "select" -> {
                val opt: Element? = e.selectFirst("option[selected]") ?: e.selectFirst("option")
                opt?.let { it.attr("value").ifEmpty { it.text() } }
            }
            else -> if (e.hasAttr("value")) e.attr("value") else null
        }
    }

    private fun css(a: Array<Value>): Any? {
        val e = elements.firstOrNull() ?: return null
        val styles = e.attr("style").split(';').mapNotNull {
            val i = it.indexOf(':')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap(LinkedHashMap())
        val k = a.getOrNull(0)
        return when {
            k == null || k.isNull -> ProxyObject.fromMap(HashMap<String, Any>(styles))
            a.size >= 2 -> {
                val prop = k.asString()
                elements.forEach { el ->
                    val s = el.attr("style").split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$prop:") && !it.startsWith("$prop :") }
                    el.attr("style", (s + "$prop: ${str(a[1])}").joinToString("; "))
                }
                this
            }
            else -> styles[k.asString()]
        }
    }

    private fun index(a: Array<Value>): Long {
        val q = a.getOrNull(0)
        val first = nodes.firstOrNull() ?: return -1
        return when {
            q == null || q.isNull -> {
                val parent = parentAny(first) ?: return 0
                parent.children().indexOfFirst { it === first }.toLong()
            }
            q.isString -> (root.execute(q) as CheerioSelection).nodes.indexOfFirst { it === first }.toLong()
            else -> {
                val target = nodesOf(q)?.firstOrNull() ?: return -1
                nodes.indexOfFirst { it === target }.toLong()
            }
        }
    }

    private fun targetsOf(v: Value): List<Node> =
        if (v.isString) (root.execute(v) as CheerioSelection).nodes else nodesOf(v) ?: emptyList()

    /** Insert [content] into each target; node content is moved into the last target and cloned for the rest. */
    private fun <T : Node> eachTarget(targets: List<T>, content: Array<Value>, insert: (T, List<Node>) -> Unit) {
        targets.forEachIndexed { i, t -> insert(t, contentNodes(content, clone = i < targets.size - 1)) }
    }

    private fun <T : Node> eachTarget(targets: List<T>, content: List<Node>, insert: (T, List<Node>) -> Unit) {
        targets.forEachIndexed { i, t -> insert(t, if (i < targets.size - 1) content.map { it.clone() } else content) }
    }

    private fun contentNodes(args: Array<Value>, clone: Boolean): List<Node> = args.flatMap { v ->
        when {
            v.isNull -> emptyList()
            v.isString -> root.fragment(v.asString())
            v.isNumber || v.isBoolean -> root.fragment(v.toString())
            else -> (nodesOf(v) ?: emptyList()).map { if (clone) it.clone() else it }
        }
    }

    private fun matcher(v: Value?, matchNone: Boolean = false): (Int, Node) -> Boolean {
        if (v == null || v.isNull) return { _, _ -> !matchNone }
        if (v.isString) {
            val q = translateSelector(v.asString())
            return { _, n -> n is Element && n.`is`(q) }
        }
        if (v.canExecute()) return { i, n -> val p = NodeProxy(n); truthy(v.invokeMember("call", p, i.toLong(), p)) }
        val set = identitySet(nodesOf(v) ?: emptyList())
        return { _, n -> n in set }
    }

    private fun filterBy(ns: List<Node>, v: Value?): List<Node> {
        if (v == null || v.isNull) return ns
        val m = matcher(v)
        return ns.filterIndexed { i, n -> m(i, n) }
    }

    private fun classes(a: Array<Value>): List<String> =
        a.getOrNull(0)?.takeIf { it.isString }?.asString()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    private fun fn(body: (Array<Value>) -> Any?): ProxyExecutable = ProxyExecutable { args -> body(args) }

    companion object {
        private val KEYS = listOf(
            "length", "text", "html", "toString", "attr", "removeAttr", "prop", "data", "val", "css",
            "hasClass", "addClass", "removeClass", "toggleClass",
            "find", "children", "contents", "parent", "parents", "parentsUntil", "closest",
            "next", "prev", "nextAll", "prevAll", "nextUntil", "prevUntil", "siblings",
            "filter", "not", "is", "has", "first", "last", "eq", "slice", "add", "addBack", "end",
            "each", "map", "get", "toArray", "index",
            "remove", "empty", "append", "prepend", "after", "before", "appendTo", "prependTo",
            "insertAfter", "insertBefore", "replaceWith", "wrap", "unwrap", "clone",
        ).toSet()
    }
}

/** Result of `.map()`: arbitrary values, or a node selection when the callback returned nodes. */
class MappedSelection(private val items: List<Any?>, private val root: CheerioRoot) : ProxyArray, ProxyObject {

    override fun get(index: Long): Any? = items.getOrNull(index.toInt())
    override fun set(index: Long, value: Value?) = throw UnsupportedOperationException("MappedSelection is read-only")
    override fun getSize(): Long = items.size.toLong()

    private fun asNodes(): List<Node>? {
        val out = ArrayList<Node>()
        for (item in items) {
            val ns = (item as? Value)?.let { nodesOf(it) } ?: return null
            out.addAll(ns)
        }
        return out
    }

    override fun getMember(key: String): Any? {
        when (key) {
            "length" -> return items.size.toLong()
            "get" -> return ProxyExecutable { a ->
                if (a.isEmpty() || a[0].isNull) ProxyArray.fromList(items)
                else a[0].asInt().let { i -> items.getOrNull(if (i < 0) items.size + i else i) }
            }
            "toArray" -> return ProxyExecutable { ProxyArray.fromList(items) }
            "toString" -> return ProxyExecutable { items.joinToString(",") }
        }
        // Node results behave like a normal selection (e.g. `.map(...).text()`).
        val ns = asNodes() ?: return null
        return CheerioSelection(ns, root).getMember(key)
    }

    override fun getMemberKeys(): Any = ProxyArray.fromArray("length", "get", "toArray", "toString")
    override fun hasMember(key: String): Boolean = getMember(key) != null
    override fun putMember(key: String, value: Value?) {}
    override fun removeMember(key: String): Boolean = false
}

/** A raw DOM node in domhandler's shape, as cheerio hands to callbacks and indexing. */
class NodeProxy(val node: Node) : ProxyObject {

    override fun getMember(key: String): Any? = when (key) {
        "type" -> when (node) {
            is Document -> "root"
            is Element -> when (node.normalName()) { "script" -> "script"; "style" -> "style"; else -> "tag" }
            is Comment -> "comment"
            else -> "text"
        }
        "nodeType" -> when (node) { is Document -> 9L; is Element -> 1L; is Comment -> 8L; else -> 3L }
        "name", "tagName" -> (node as? Element)?.takeIf { it !is Document }?.normalName()
        "attribs" -> (node as? Element)?.let { e -> ProxyObject.fromMap(HashMap<String, Any>().also { m -> e.attributes().forEach { m[it.key] = it.value } }) }
        "data", "nodeValue" -> when (node) {
            is TextNode -> node.wholeText
            is DataNode -> node.wholeData
            is Comment -> node.data
            else -> null
        }
        "children", "childNodes" -> ProxyArray.fromList(node.childNodes().map { NodeProxy(it) })
        "parent", "parentNode" -> node.parentNode()?.let(::NodeProxy)
        "next", "nextSibling" -> node.nextSibling()?.let(::NodeProxy)
        "prev", "previousSibling" -> node.previousSibling()?.let(::NodeProxy)
        "firstChild" -> node.childNodes().firstOrNull()?.let(::NodeProxy)
        "lastChild" -> node.childNodes().lastOrNull()?.let(::NodeProxy)
        "toString" -> ProxyExecutable { node.outerHtml() }
        else -> null
    }

    override fun getMemberKeys(): Any = ProxyArray.fromArray(*KEYS.toTypedArray())
    override fun hasMember(key: String): Boolean = key in KEYS

    override fun putMember(key: String, value: Value?) {
        if ((key == "data" || key == "nodeValue") && node is TextNode && value != null) node.text(str(value))
    }

    companion object {
        private val KEYS = listOf(
            "type", "nodeType", "name", "tagName", "attribs", "data", "nodeValue", "children", "childNodes",
            "parent", "parentNode", "next", "nextSibling", "prev", "previousSibling", "firstChild", "lastChild", "toString",
        ).toSet()
    }
}

// ---- shared helpers ----

internal fun nodesOf(v: Value): List<Node>? {
    if (v.isProxyObject) {
        when (val p = v.asProxyObject<Proxy>()) {
            is CheerioSelection -> return p.nodes
            is NodeProxy -> return listOf(p.node)
            is MappedSelection -> return (0 until p.size).flatMap { i -> (p.get(i.toLong()) as? Value)?.let { nodesOf(it) } ?: emptyList() }
        }
    }
    if (v.hasArrayElements()) return (0 until v.arraySize).flatMap { nodesOf(v.getArrayElement(it)) ?: emptyList() }
    return null
}

internal fun truthy(v: Value?): Boolean = when {
    v == null || v.isNull -> false
    v.isBoolean -> v.asBoolean()
    v.isNumber -> v.asDouble().let { it != 0.0 && !it.isNaN() }
    v.isString -> v.asString().isNotEmpty()
    else -> true
}

private fun str(v: Value): String = if (v.isString) v.asString() else v.toString()

// jsoup lacks jQuery's :first/:last; the plugins using them target sibling runs, where *-of-type matches.
private fun translateSelector(q: String): String =
    q.replace(Regex(":first(?![-\\w])"), ":first-of-type").replace(Regex(":last(?![-\\w])"), ":last-of-type")

private fun selectIn(scope: List<Node>, query: String): List<Node> {
    val q = translateSelector(query)
    val out = ArrayList<Node>()
    for (n in scope) {
        if (n !is Element) continue
        n.select(q).forEach { if (it !== n) out.add(it) }
    }
    return if (scope.size > 1) uniq(out) else out
}

private fun textContent(n: Node): String = when (n) {
    is TextNode -> n.wholeText
    is DataNode -> n.wholeData
    is Element -> StringBuilder().also { appendText(n, it) }.toString()
    else -> ""
}

private fun appendText(n: Node, sb: StringBuilder) {
    for (c in n.childNodes()) when (c) {
        is TextNode -> sb.append(c.wholeText)
        is DataNode -> sb.append(c.wholeData)
        is Element -> appendText(c, sb)
    }
}

private fun setText(n: Node, s: String) {
    when (n) {
        is Element -> n.text(s)
        is TextNode -> n.text(s)
    }
}

private fun parentEl(n: Node): Element? = (n.parentNode() as? Element)?.takeIf { it !is Document }
private fun parentAny(n: Node): Element? = n.parentNode() as? Element

private fun ancestors(n: Node): List<Element> {
    val out = ArrayList<Element>()
    var p = parentEl(n)
    while (p != null) { out.add(p); p = parentEl(p) }
    return out
}

private fun isAncestor(ancestor: Node, n: Node): Boolean {
    var p = n.parentNode()
    while (p != null) { if (p === ancestor) return true; p = p.parentNode() }
    return false
}

private fun nextEl(n: Node): Element? {
    var s = n.nextSibling()
    while (s != null && s !is Element) s = s.nextSibling()
    return s as Element?
}

private fun prevEl(n: Node): Element? {
    var s = n.previousSibling()
    while (s != null && s !is Element) s = s.previousSibling()
    return s as Element?
}

private fun siblingsAfter(n: Node): List<Element> = generateSequence(nextEl(n)) { nextEl(it) }.toList()
private fun siblingsBefore(n: Node): List<Element> = generateSequence(prevEl(n)) { prevEl(it) }.toList()

private fun identitySet(ns: List<Node>): MutableSet<Node> =
    Collections.newSetFromMap(IdentityHashMap<Node, Boolean>()).also { it.addAll(ns) }

private fun <T : Node> uniq(ns: List<T>): List<T> {
    val seen = identitySet(emptyList())
    return ns.filter { seen.add(it) }
}

private fun kebab(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
private fun camel(s: String) = s.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
