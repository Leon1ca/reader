// Original host protocol. Only this trusted top-level document can call the native port.
import './vendor/foliate/paginator.js'
let paginator = document.getElementById('renderer'), initialized = false, publicationCount = 0
const base = new URL('./', location.href)
let identity, epoch = 0, commandId = 0, opening = true, closed = false, lastTarget, reportTimer
let chain = Promise.resolve()
let currentProfile = ''
let viewportWidth = 0, viewportHeight = 0
function syncViewport() {
    if (closed) return false
    const width = globalThis.visualViewport?.width ?? innerWidth
    const height = globalThis.visualViewport?.height ?? innerHeight
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return false
    const changed = Math.abs(width-viewportWidth) > 0.01 || Math.abs(height-viewportHeight) > 0.01
    viewportWidth = width; viewportHeight = height
    // Android WebView can cache zero-valued percentage/vh heights when mounted
    // before native measurement. Bind the shell to its actual CSS-pixel viewport.
    document.documentElement.style.height = `${height}px`
    return changed
}
syncViewport()
let userInteractionUntil = 0
function userIntent(event) {
    if (event.isTrusted && !opening && !closed) userInteractionUntil = performance.now() + 700
}
for (const type of ['wheel','touchmove','pointerdown','keydown']) addEventListener(type,userIntent,{capture:true,passive:true})
let stabilizedSequence = 0, relocationDraft, latestRequestedId = 0
const superseded = Symbol('superseded')
let operationDeadline = 0
function bounded(promise) {
    const id = commandId
    return new Promise((resolve,reject) => {
        const timer = setInterval(() => {
            if (closed || id !== latestRequestedId) { clearInterval(timer); reject(superseded) }
            else if (performance.now() >= operationDeadline) { clearInterval(timer); reject(new Error('Reader operation timed out')) }
        }, 40)
        Promise.resolve(promise).then(value => {clearInterval(timer);resolve(value)},error => {clearInterval(timer);reject(error)})
    })
}
const events = []
function emit(type, payload = {}) {
    if (closed || !identity) return
    const event = { protocol: 1, ...identity, layoutEpoch: epoch, commandId, type, ...payload }
    if (events.length === 32) events.shift()
    events.push(event)
    globalThis.ReaderEvents?.postMessage(JSON.stringify(event))
}
function rangeFor(doc, target) {
    const el = [...doc.querySelectorAll('[data-block]')].find(x => x.dataset.block === target.blockId)
    if (!el) throw new Error('Missing stable content block')
    if (el.dataset.element) return el
    const nodes = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    let node = nodes.nextNode(), remaining = target.character, last = node
    while (node) { last = node; if (remaining <= node.length) break; remaining -= node.length; node = nodes.nextNode() }
    node ??= last
    const range = doc.createRange()
    if (node) range.setStart(node, Math.min(remaining, node.length))
    else range.setStart(el, 0)
    range.collapse(true)
    return range
}
function targetFor(range, index) {
    if (!range) return null
    const doc = range.startContainer.ownerDocument
    if (range.collapsed && range.startContainer === doc.body) {
        const viewport = paginator.getBoundingClientRect(), frame = doc.defaultView.frameElement.getBoundingClientRect()
        const image = [...doc.querySelectorAll('[data-element]')].find(el => {
            const rect = el.getBoundingClientRect()
            return rect.top + frame.top < viewport.bottom && rect.bottom + frame.top > viewport.top && rect.left + frame.left < viewport.right && rect.right + frame.left > viewport.left
        })
        if (image) return {section:index,blockId:image.dataset.block,character:0}
    }
    let el = range.startContainer.nodeType === Node.ELEMENT_NODE ? range.startContainer : range.startContainer.parentElement
    el = el?.closest('[data-block]')
    if (!el) {
        // Range can start at body/p boundaries. Select the first marked node intersecting it.
        el = [...doc.querySelectorAll('[data-block]')].find(x => range.intersectsNode(x))
        if (!el) return null
        return { section: index, blockId: el.dataset.block, character: 0 }
    }
    const before = doc.createRange()
    before.selectNodeContents(el)
    before.setEnd(range.startContainer, range.startOffset)
    const character = Math.min(Number(el.dataset.length), before.toString().length)
    return { section: index, blockId: el.dataset.block, character }
}
function anchorGeometry(target) {
    const entry = paginator.getContents().find(x => x.index === target?.section)
    if (!entry?.doc?.body) return {visible:false}
    try {
        const anchor = rangeFor(entry.doc,target)
        const range = anchor.cloneRange?.()
        if (range?.startContainer?.nodeType === Node.TEXT_NODE && range.startOffset < range.startContainer.length)
            range.setEnd(range.startContainer,range.startOffset+1)
        const rect = (range ?? anchor).getBoundingClientRect()
        const frame = entry.doc.defaultView.frameElement.getBoundingClientRect(), viewport = paginator.getBoundingClientRect()
        const left = rect.left+frame.left, top = rect.top+frame.top
        return {left,top,width:rect.width,height:rect.height,visible:left+rect.width>viewport.left && left<viewport.right && top+rect.height>viewport.top && top<viewport.bottom}
    } catch {return {visible:false}}
}
async function stable(target) {
    const deadline = performance.now() + 12000
    let signature, frames = 0, anchored = false
    while (!closed && performance.now() < deadline) {
        if (commandId !== latestRequestedId) throw superseded
        if (syncViewport()) anchored = false
        const contents = paginator.getContents()
        if (!contents.length || contents.some(({doc}) => !doc.querySelector('[data-block]'))) {
            // A preloader exposes an about:blank view before its section's load event.
            await new Promise(resolve => setTimeout(resolve,50))
            continue
        }
        await bounded(Promise.all(contents.map(({doc}) => doc.fonts.ready)))
        const listeners = new AbortController()
        try {
            await bounded(Promise.all(contents.flatMap(({doc}) => [...doc.images].map(image => image.complete ? Promise.resolve() :
                new Promise((resolve,reject) => {
                    image.addEventListener('load',resolve,{once:true,signal:listeners.signal})
                    image.addEventListener('error',()=>reject(new Error('Article image failed to load')),{once:true,signal:listeners.signal})
                })))))
        } finally { listeners.abort() }
        if (contents.some(({doc}) => [...doc.images].some(image => !image.naturalWidth))) throw new Error('Article image failed to load')
        if (target && !anchored) {
            anchored = true
            await bounded(paginator.goTo({ index: target.section, anchor: doc => rangeFor(doc, target) }))
        }
        await new Promise(resolve => setTimeout(resolve, 50))
        const now = paginator.getContents()
        const next = JSON.stringify([stabilizedSequence, now.map(({index,doc}) => [index,doc.fonts.status,doc.body?.scrollWidth,doc.body?.scrollHeight]),target ? anchorGeometry(target) : null])
        frames = next === signature ? frames + 1 : 0
        signature = next
        if (frames >= 3 && now.every(({doc}) => doc.fonts.status === 'loaded') && (!target || anchorGeometry(target).visible)) return
    }
    throw new Error('Reader layout did not stabilize')
}
function installListeners(instance) {
instance.addEventListener('load', ({ detail: { doc, index } }) => {
    if (instance !== paginator || closed) return
    // Content is inert even when it has same-origin DOM access for Range measurement.
    doc.addEventListener('click', event => {
        if (event.target.closest?.('a')) event.preventDefault()
    }, true)
    for (const type of ['wheel','touchmove','pointerdown','keydown']) doc.addEventListener(type,event => {
        if (instance === paginator) userIntent(event)
    },{capture:true,passive:true})
    emit('sectionLoaded', { section: index, activeViews: paginator.getContents().length })
})
instance.addEventListener('relocate', ({ detail }) => {
    if (instance !== paginator) return
    const target = targetFor(detail.range, detail.index)
    if (!target || closed) return
    relocationDraft = target
    if (opening) return
    // Layout/anchor restoration also produces container-scroll. Keep the durable
    // requested source character while it remains visible, unless the user navigated.
    // Genuine scroll leaves it behind (or carries trusted input intent); page commands
    // choose their first visible source explicitly after settling.
    if (lastTarget && performance.now() > userInteractionUntil && anchorGeometry(lastTarget).visible) return
    lastTarget = target
    clearTimeout(reportTimer)
    // Never send Range/page fractions as durable locators; only stable source block offsets.
    reportTimer = setTimeout(() => emit('relocated', { target, reason: detail.reason ?? 'scroll' }), 50)
})
instance.addEventListener('stabilized', () => { if (instance === paginator && !closed) { stabilizedSequence++; emit('stabilized', { activeViews: paginator.getContents().length }) } })
}
installListeners(paginator)
function replacePaginator() {
    const previous = paginator
    const next = document.createElement('foliate-paginator')
    next.id = 'renderer'
    paginator = next // reject all late events from the previous engine before destroying it
    try { previous.destroy() } catch {}
    previous.replaceWith(next)
    installListeners(next)
    stabilizedSequence = 0
}
async function execute(command) {
    if (closed || command.protocol !== 1 || command.sessionId !== identity?.sessionId || command.revision !== identity?.revision || command.commandId <= commandId || command.commandId !== latestRequestedId) return
    clearTimeout(reportTimer)
    opening = true
    epoch = command.layoutEpoch
    commandId = command.commandId
    operationDeadline = performance.now() + 12000
    try {
        const payload = command.payload
        currentProfile = payload.profileId ?? currentProfile
        if (!Array.prototype.at || !Array.prototype.findLastIndex || !globalThis.ResizeObserver) throw new Error('请更新系统 WebView 以使用阅读内核')
        const rebuildForFlow = command.type === 'setRules' && payload.flow !== paginator.getAttribute('flow')
        if (command.type === 'open' || rebuildForFlow) {
            if (initialized) replacePaginator()
            initialized = true
            if (command.type === 'open') publicationCount = payload.sectionCount
            if (!Number.isInteger(publicationCount) || publicationCount < 1 || publicationCount > 2100000) throw new Error('Invalid publication section count')
            // Array-compatible lazy descriptors: no per-section object/closure for an entire large book.
            const sections = new Proxy([], { has(array,key) {
                if (typeof key === 'string' && /^(0|[1-9][0-9]*)$/.test(key)) return Number(key) < publicationCount
                return Reflect.has(array,key)
            }, get(array,key,receiver) {
                if (key === 'length') return publicationCount
                if (typeof key === 'string' && /^(0|[1-9][0-9]*)$/.test(key)) {
                    const index = Number(key)
                    if (index >= publicationCount) return undefined
                    return {id:String(index),linear:'yes',load:()=>new URL(`sections/${index}.html`,base).href,unload:()=>{},size:1}
                }
                return Reflect.get(array,key,receiver)
            } })
            for (const [key,value] of Object.entries(payload.geometry ?? {})) paginator.setAttribute(key,String(value))
            paginator.open({ dir: 'ltr', sections })
            paginator.setAttribute('flow', payload.flow)
            paginator.setStyles([payload.beforeAuthor, payload.afterAuthor])
            lastTarget = payload.target
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: doc => rangeFor(doc, lastTarget) }))
            await stable(lastTarget)
        } else if (command.type === 'goTo') {
            lastTarget = payload.target
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: doc => rangeFor(doc, lastTarget) }))
            await stable(lastTarget)
        } else if (command.type === 'setRules') {
            lastTarget = payload.target
            for (const [key,value] of Object.entries(payload.geometry ?? {})) paginator.setAttribute(key,String(value))
            paginator.setAttribute('flow', payload.flow)
            paginator.setStyles([payload.beforeAuthor, payload.afterAuthor])
            await stable(lastTarget)
        } else if (command.type === 'next' || command.type === 'prev') {
            await bounded(paginator[command.type]())
            await stable()
            if (relocationDraft) lastTarget = relocationDraft
        } else throw new Error('Unknown renderer command')
        if (commandId !== latestRequestedId || closed) throw superseded
        opening = false
        emit('ready', { profileId: currentProfile, activeViews: paginator.getContents().length, nearby:Boolean(lastTarget?.nearby) })
        // For explicit anchoring, preserve the requested character within its visible page.
        // Normal page turns/scroll events continue to report first visible characters.
        emit('relocated', { target: lastTarget, reason: command.type === 'next' || command.type === 'prev' ? 'page' : 'anchor' })
    } catch (error) { if (error !== superseded && !closed && commandId === latestRequestedId) { opening = false;emit('error', { message: String(error?.message ?? error).slice(0, 300) }) } }
}
globalThis.readerHost = Object.freeze({
    command(command) {
        if (command.protocol !== 1 || command.sessionId !== identity.sessionId || command.revision !== identity.revision || !Number.isInteger(command.commandId) || command.commandId <= latestRequestedId) return
        latestRequestedId = command.commandId
        chain = chain.then(() => execute(command))
    },
    close() { closed = true; clearTimeout(reportTimer); try { paginator.destroy() } catch {} },
    // Read-only diagnostics shared by device and desktop tests; no content/native privileges.
    diagnostics() {
        return { protocol: 1, identity, layoutEpoch: epoch, commandId, loaded: true, flow: paginator.getAttribute('flow'),
            activeViews: paginator.getContents().length, target: lastTarget, actualVisibleTarget: relocationDraft, anchorGeometry:anchorGeometry(lastTarget), events: [...events],
            contents: paginator.getContents().map(({ index, doc }) => ({ section: index, blocks: doc.querySelectorAll('[data-block]').length,
                textLength: doc.body?.textContent.length ?? 0, url: doc.URL, state: doc.readyState, scriptExecuted: doc.defaultView?.attacked === true,
                fontSize: doc.body ? getComputedStyle(doc.body).fontSize : '', fontFamily: doc.body ? getComputedStyle(doc.body).fontFamily : '' })) }
    },
})
identity = { sessionId: base.pathname.split('/')[2], revision: decodeURIComponent(base.pathname.split('/')[3]) }
function viewportResized() { if (syncViewport() && !opening && !closed) emit('layoutInvalidated') }
addEventListener('resize',viewportResized)
globalThis.visualViewport?.addEventListener('resize',viewportResized)
emit('booted')
