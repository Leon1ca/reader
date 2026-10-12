import {installInput,applyInsets} from './input.js'
import {textIntervals,mergeIntervals,visibleFrame,fontGeometry,compactSignature,visibleCaret,compactTextRanges,compactElements,paginationOffset} from './viewport.js'
// Original host protocol. Only this trusted top-level document can call the native port.
import './vendor/foliate/paginator.js'
let paginator = document.getElementById('renderer'), initialized = false, publicationCount = 0
const base = new URL('./', location.href)
let identity, epoch = 0, commandId = 0, opening = true, closed = false, lastTarget, reportTimer
let chain = Promise.resolve()
let paginationBase=''
let relocationDetail
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
let operationDeadline = 0, operationPhase = 'idle'
function bounded(promise,phaseName) {
    if(phaseName)operationPhase=phaseName
    const id = commandId, owner = paginator
    return new Promise((resolve,reject) => {
        const timer = setInterval(() => {
            if (closed || id !== latestRequestedId) { clearInterval(timer); owner.cancelNavigation(); reject(superseded) }
            else if (performance.now() >= operationDeadline) { clearInterval(timer); owner.cancelNavigation(); reject(new Error('Reader operation timed out')) }
        }, 40)
        Promise.resolve(promise).then(value => {clearInterval(timer);resolve(value)},error => {clearInterval(timer);reject(error)})
    })
}
// Only the requested section and the frames actually on screen gate an open or a
// page turn; preloaded neighbours finish in the background and are checked once visible.
function relevant(contents,target) {
    const rect=paginator.getBoundingClientRect()
    const list=contents.filter(({index,doc})=>{
        if(index===target?.section)return true
        try {return Boolean(doc?.body)&&visibleFrame(doc,rect)} catch {return false}
    })
    return list.length?list:contents
}
// The renderer stays transparent until the first stable, font-ready layout, so a
// book never flashes in a fallback face or at a provisional position.
function conceal() { document.documentElement.dataset.readerPending = '' }
function reveal() { delete document.documentElement.dataset.readerPending }
// Warm the shared CJK face once per renderer: section documents then reuse the decoded font.
if (location.origin === 'https://appassets.androidplatform.net') {
    const preload = document.createElement('link')
    Object.assign(preload,{rel:'preload',as:'font',type:'font/ttf',crossOrigin:'anonymous',href:'/fonts/LXGWWenKaiGBScreen.ttf'})
    document.head.append(preload)
}

const events = []
function emit(type, payload = {}) {
    if(type==='relocated'){try{payload.viewport=readingViewport()}catch(error){emit('error',{message:String(error.message)});return}}
    if (closed || !identity) return
    const event = { protocol: 1, ...identity, layoutEpoch: epoch, commandId, type, ...payload }
    if (events.length === 32) events.shift()
    events.push(event)
    const raw=JSON.stringify(event)
    if(raw.length>65536){if(type!=='error')emit('error',{message:'Reader event exceeds bridge budget'});return}
    globalThis.ReaderEvents?.postMessage(raw)
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
function readingViewport() {
    const rect=paginator.getBoundingClientRect(),visible=paginator.getContents().filter(x=>x.doc?.body&&x.doc.readyState==='complete'&&visibleFrame(x.doc,rect)).sort((a,b)=>a.index-b.index),ranges=[],elements=[],points=[]
    for(const {index,doc} of visible) { const grouped=new Map();for(const value of textIntervals(doc,rect)){const el=value.node.parentElement?.closest('[data-block]');if(!el)continue;const before=doc.createRange();before.selectNodeContents(el);before.setEnd(value.node,value.start);const start=before.toString().length;const values=grouped.get(el.dataset.block)??[];values.push({start,end:start+value.end-value.start});grouped.set(el.dataset.block,values)}for(const [blockId,values] of grouped)for(const span of mergeIntervals(values))ranges.push({start:{section:index,blockId,character:span.start},end:{section:index,blockId,character:span.end}}) }
    for(const {index,doc} of visible)for(const el of doc.querySelectorAll('[data-block]')) {
        const target={section:index,blockId:el.dataset.block,character:Number(el.dataset.length)}
        if(el.dataset.element) { const box=el.getBoundingClientRect(),frame=doc.defaultView.frameElement.getBoundingClientRect();if(box.width>.1&&box.height>.1&&box.bottom+frame.top>rect.top&&box.top+frame.top<rect.bottom&&box.right+frame.left>rect.left&&box.left+frame.left<rect.right)elements.push({...target,character:0});continue }
        if(visibleCaret(doc,rangeFor(doc,target),rect))points.push(target)
    }
    if(ranges.length>16384||elements.length>16384||points.length>256)throw new Error('Visible range exceeds bridge budget')
    const detail=relocationDetail,current=ranges[0]?.start.section??elements[0]?.section??points[0]?.section??(visible.some(x=>x.index===detail?.index)?detail.index:visible[0]?.index),samples=[]
    // Each frame is measured against the public engine's actual pagination tile.
    // A dual-column spread can contain two different sections; relocate.size is
    // based on the primary view and is not a count for every visible section.
    for(const {index,doc} of visible) {
        const frame=doc.defaultView.frameElement.getBoundingClientRect(),style=getComputedStyle(doc.body),vertical=/^(vertical|sideways)/.test(style.writingMode),side=paginator.sideProp
        const size=paginator.size/(paginator.scrolled?1:paginator.columnCount),length=frame[side]
        if(!(size>0&&length>0))continue
        const backwards=side==='width'&&(vertical?style.writingMode.endsWith('-rl'):style.direction==='rtl')
        const delta=paginationOffset(paginator,frame,backwards)
        // Paginated tiles are discrete. Native scroll positions quantize to CSS
        // pixels (e.g. 692 instead of a 692.42px tile); choose the actual tile,
        // while continuous scrolling retains its fractional coordinate.
        const units=paginator.scrolled?Math.max(.000001,length/size):Math.max(1,Math.round(length/size)),offset=Math.max(0,Math.min(units-.000001,paginator.scrolled?delta/size:Math.round(delta/size)))
        samples.push({section:index,page:Math.min(Math.ceil(units),Math.floor(offset+1e-6)+1),pages:Math.ceil(units),units,offset:paginator.scrolled?offset:Math.min(Math.ceil(units)-1,Math.floor(offset+1e-6)),scrolled:paginator.scrolled,estimated:paginator.scrolled,signature:compactSignature([paginator.columnCount,fontGeometry([doc])])})
    }
    return {base:paginationBase,signature:compactSignature([rect.width,rect.height,paginator.getAttribute('flow')]),currentSection:current,samples,ranges:ranges.length<=256?ranges:[],...(ranges.length>256?{rangeGroups:compactTextRanges(ranges)}:{}),elements:elements.length<=256?elements:[],...(elements.length>256?{elementGroups:compactElements(elements)}:{}),points,visiblePages:[],atEnd:Boolean(paginator.atEnd&&visible.some(x=>x.index===publicationCount-1))}
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
// A real frame boundary, with stale/closed/deadline cancellation supplied by
// bounded(). Background documents cannot publish a visible Reader position.
const nextFrame = () => new Promise(resolve => requestAnimationFrame(resolve))
function pageSignature(target) {
    const contents=relevant(paginator.getContents(),target)
    return JSON.stringify([stabilizedSequence,contents.map(({index,doc})=>[index,doc.fonts.status,doc.body?.scrollWidth,doc.body?.scrollHeight]),target,anchorGeometry(target)])
}
async function settleLoadedPage(target) {
    let signature, equalFrames=0
    for(let frame=0;frame<6;frame++) {
        if(closed || commandId!==latestRequestedId)throw superseded
        if(syncViewport())return false
        const contents=relevant(paginator.getContents())
        if(!contents.length || contents.some(({doc})=>!loadedPageDocument(doc)))return false
        await bounded(nextFrame(), 'pageFrame')
        if(closed || commandId!==latestRequestedId)throw superseded
        if(syncViewport() || relevant(paginator.getContents()).some(({doc})=>!loadedPageDocument(doc)))return false
        const visible=target ?? relocationDraft
        if(!visible || !anchorGeometry(visible).visible)return false
        const next=pageSignature(visible)
        equalFrames=next===signature ? equalFrames+1 : 0
        signature=next
        if(equalFrames>=2)return true
    }
    return false
}
function loadedPageDocument(doc) {
    return doc.readyState==='complete' && Boolean(doc.querySelector('[data-block]')) &&
        doc.fonts.status==='loaded' && [...doc.images].every(image=>image.complete&&image.naturalWidth>0)
}
async function stable(target) {
    const requestedId = commandId
    const deadline = performance.now() + 12000
    let signature, frames = 0, anchored = false
    while (!closed && performance.now() < deadline) {
        if (commandId !== latestRequestedId) throw superseded
        if (syncViewport()) anchored = false
        const contents = relevant(paginator.getContents(),target)
        if (!contents.length || contents.some(({doc}) => !doc.querySelector('[data-block]'))) {
            // A preloader exposes an about:blank view before its section's load event.
            await new Promise(resolve => setTimeout(resolve,50))
            continue
        }
        await bounded(Promise.all(contents.map(({doc}) => doc.fonts.ready)), 'fonts')
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
            // Fonts can reflow the section after the initial navigation; re-anchor only then.
            if (!anchorGeometry(target).visible) await bounded(paginator.goTo({ index: target.section, anchor: doc => {
                if (closed || commandId !== requestedId || latestRequestedId !== requestedId) throw superseded
                return rangeFor(doc, target)
            } }), 'anchorGoTo')
        }
        await bounded(nextFrame(), 'layoutFrame')
        const now = relevant(paginator.getContents(),target)
        const next = pageSignature(target)
        frames = next === signature ? frames + 1 : 0
        signature = next
        if (frames >= 3 && now.every(({doc}) => doc.fonts.status === 'loaded') && (!target || anchorGeometry(target).visible)) return
    }
    throw new Error('Reader layout did not stabilize')
}
function installListeners(instance) {
instance.addEventListener('load', ({ detail: { doc, index } }) => {
    if (instance !== paginator || closed) return
    installInput(doc,{active:()=>instance===paginator&&!opening&&!closed,flow:()=>paginator.getAttribute('flow'),direction:()=> 'ltr',emit})
    let selectionTimer=0
    const selection=()=>{
        if(opening||closed||instance!==paginator)return
        const selected=doc.getSelection()
        if(!selected||selected.isCollapsed||!selected.rangeCount){emit('selectionCleared');return}
        const range=selected.getRangeAt(0)
        const anchor=(node,offset)=>{
            const el=(node.nodeType===1?node:node.parentElement)?.closest('[data-block]');if(!el)return null
            const prefix=doc.createRange();prefix.selectNodeContents(el);prefix.setEnd(node,offset)
            return {section:index,blockId:el.dataset.block,character:Math.min(Number(el.dataset.length),prefix.toString().length)}
        }
        const a=anchor(range.startContainer,range.startOffset),b=anchor(range.endContainer,range.endOffset),text=selected.toString()
        if(a&&b&&text.length>0&&text.length<=2000)emit('blockSelectionRequested',{start:a,end:b,text})
    }
    for(const name of ['pointerup','selectionchange'])doc.addEventListener(name,e=>{
        if(!e.isTrusted||performance.now()>userInteractionUntil)return
        clearTimeout(selectionTimer);selectionTimer=setTimeout(selection,200)
    },true)
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
    relocationDetail=detail
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
instance.addEventListener('stabilized', () => { if (instance === paginator && !closed) stabilizedSequence++ })
}
installListeners(paginator)
installInput(document,{active:()=>!opening&&!closed,flow:()=>paginator.getAttribute('flow'),direction:()=>'ltr',emit})
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
        const requestedTarget = payload.target
        const requestedId = command.commandId
        const requestedAnchor = doc => {
            if (closed || commandId !== requestedId || latestRequestedId !== requestedId) throw superseded
            return rangeFor(doc, requestedTarget)
        }
        applyInsets(payload)
        if(payload.paginationBase)paginationBase=payload.paginationBase
        currentProfile = payload.profileId ?? currentProfile
        if (!Array.prototype.at || !Array.prototype.findLastIndex || !globalThis.ResizeObserver) throw new Error('请更新系统 WebView 以使用阅读内核')
        const rebuildForFlow = command.type === 'setRules' && payload.flow !== paginator.getAttribute('flow')
        if (command.type === 'open' || rebuildForFlow) {
            conceal()
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
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: requestedAnchor }), 'initialGoTo')
            await stable(lastTarget)
        } else if (command.type === 'goTo') {
            lastTarget = payload.target
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: requestedAnchor }), 'initialGoTo')
            await stable(lastTarget)
        } else if (command.type === 'setRules') {
            lastTarget = payload.target
            for (const [key,value] of Object.entries(payload.geometry ?? {})) paginator.setAttribute(key,String(value))
            paginator.setAttribute('flow', payload.flow)
            paginator.setStyles([payload.beforeAuthor, payload.afterAuthor])
            await stable(lastTarget)
        } else if (command.type === 'next' || command.type === 'prev') {
            await bounded(paginator[command.type]())
            if(!await settleLoadedPage())await stable()
            if (relocationDraft) lastTarget = relocationDraft
        } else throw new Error('Unknown renderer command')
        if (commandId !== latestRequestedId || closed) throw superseded
        opening = false
        reveal()
        emit('ready', { profileId: currentProfile, activeViews: paginator.getContents().length, nearby:Boolean(lastTarget?.nearby) })
        // For explicit anchoring, preserve the requested character within its visible page.
        // Normal page turns/scroll events continue to report first visible characters.
        emit('relocated', { target: lastTarget, reason: command.type === 'next' || command.type === 'prev' ? 'page' : 'anchor' })
    } catch (error) { if (error !== superseded && !closed && commandId === latestRequestedId) { opening = false;reveal();emit('error', { message: String(error?.message ?? error).slice(0, 300) }) } }
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
            operationPhase, activeViews: paginator.getContents().length, target: lastTarget, actualVisibleTarget: relocationDraft, anchorGeometry:anchorGeometry(lastTarget), events: [...events],
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
