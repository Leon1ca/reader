import {installInput,applyInsets} from './input.js'
import {textIntervals,mergeIntervals,visibleFrame,fontGeometry,compactSignature,visibleCaret,compactTextRanges,compactElements,paginationOffset,sourceRectVisible} from './viewport.js'
// Original host protocol. Only this trusted top-level document can call the native port.
import {getDirection,getPageProgressionRTL} from './vendor/foliate/paginator.js'
let paginator = document.getElementById('renderer'), initialized = false, publicationCount = 0
const base = new URL('./', location.href)
let identity, epoch = 0, commandId = 0, opening = true, closed = false, lastTarget, reportTimer
let chain = Promise.resolve()
let descriptorCount=0
let paginationBase=''
let relocationDetail
let currentProfile = '', intentToken = '', intentCause = 'Restore', bookDirection='ltr', userIntentToken=''
// Several sections stay mounted at once, so "any loaded document" is not the page
// being read. With a `default` book direction the progression follows the section
// on screen: the last relocation's section, then the paginator's primary view.
function currentDocument() {
    const loaded=paginator.getContents().filter(value=>value.index!==undefined&&value.doc?.body)
    for(const index of [relocationDetail?.index,paginator.primaryIndex]) {
        const match=loaded.find(value=>value.index===index)
        if(match)return match.doc
    }
    return loaded[0]?.doc
}
function inputDirection(doc) {
    const content=doc?.body ? doc : currentDocument()
    return getPageProgressionRTL(bookDirection,content ? getDirection(content).rtl : false) ? 'rtl' : 'ltr'
}
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
    if (event.isTrusted && !opening && !closed) {
        userInteractionUntil = performance.now() + 700
        userIntentToken = crypto.randomUUID()
        emit('userIntent',{userIntentToken,inputType:event.type})
    }
}
for (const type of ['wheel','touchmove','pointerdown','keydown']) addEventListener(type,userIntent,{capture:true,passive:true})
let stabilizedSequence = 0, relocationDraft, latestRequestedId = 0
const superseded = Symbol('superseded')
let operationDeadline = 0, relocationRange, operationPhase = 'idle'
const fontWarnings = new WeakSet()
let failedFontDocuments = new Set()
function bounded(promise,phase) {
    const id = commandId, owner = paginator
    operationPhase = phase
    return new Promise((resolve,reject) => {
        const timer = setInterval(() => {
            if (closed || id !== latestRequestedId) { clearInterval(timer); owner.cancelNavigation(); reject(superseded) }
            else if (performance.now() >= operationDeadline) { clearInterval(timer); owner.cancelNavigation(); reject(new Error(`Reader operation timed out: phase=${phase},command=${commandId},${contentState()}`)) }
        }, 40)
        Promise.resolve(promise).then(value => {clearInterval(timer);resolve(value)},error => {clearInterval(timer);reject(error)})
    })
}
function contentState() {
    return paginator.getContents().map(({index,doc}) => `${index}:${doc.readyState}:${doc.body?.dataset.readerCanonicalLength ?? '-'}:${canonical.get(doc)?.length ?? '-'}`).join(';')
}
// Only the requested section and the frames actually on screen gate an open or a
// page turn; preloaded neighbours finish in the background and are validated once visible.
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
// A broken image must not make a whole book unopenable: hide it and continue.
function settleImage(image) {
    if (image.complete && image.naturalWidth > 0) return Promise.resolve()
    const svg = /\.svg(?:$|[?#])/i.test(image.currentSrc || image.src || '')
    if (image.complete && svg) return Promise.resolve()
    return new Promise(resolve => {
        const done = () => { if (!(image.naturalWidth > 0) && !svg) image.style.display = 'none'; resolve() }
        if (image.complete) { done(); return }
        image.addEventListener('load', done, {once:true})
        image.addEventListener('error', done, {once:true})
    })
}

const events = []
function emit(type, payload = {}) {
    if(type==='relocated'){try{payload.viewport=readingViewport();payload.atStart=paginator.atStart;payload.atEnd=paginator.atEnd;payload.pageDirection=inputDirection()}catch(error){emit('error',{message:String(error.message)});return}}
    if (closed || !identity) return
    const event = { protocol: 2, ...identity, layoutEpoch: epoch, commandId, intentToken, intentCause, type, ...payload }
    if (events.length === 32) events.shift()
    events.push(event)
    const raw=JSON.stringify(event)
    if(raw.length>65536){if(type!=='error')emit('error',{message:'Reader event exceeds bridge budget'});return}
    globalThis.ReaderEvents?.postMessage(raw)
}
// Canonical offsets count every real body TextNode, including collapsed/hidden text.
// No renderer wrapper, virtual whitespace or normalized DOM string participates.
const canonical = new WeakMap()
function textIndex(doc) {
    let value = canonical.get(doc)
    if (value) return value
    const walker = doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT)
    const runs = [], starts = new WeakMap(), strings = []
    let node, length = 0
    while ((node = walker.nextNode())) {
        starts.set(node,length); runs.push({node,start:length,end:length+node.length})
        strings.push(node.data);length += node.length
    }
    value = {runs,starts,length,text:strings.join('')};
    // The preloader can expose this same Document while its parser is still writing.
    // Never make an early relocation/diagnostic snapshot the permanent canonical index.
    if(doc.readyState==='complete' && doc.body.hasAttribute('data-reader-canonical-sha'))canonical.set(doc,value)
    return value
}
function rawRange(doc,offset) {
    const {runs,length} = textIndex(doc)
    if (!Number.isInteger(offset) || offset < 0 || offset > length) throw new Error('Invalid canonical text offset')
    const run = runs.find(x => offset < x.end) ?? runs.at(-1)
    const range = doc.createRange()
    if (run) range.setStart(run.node,Math.max(0,Math.min(offset-run.start,run.node.length)))
    else range.setStart(doc.body,0)
    range.collapse(true);return range
}
function rangeFor(doc,target) {
    if (target.kind === 'ELEMENT') {
        const el = [...doc.querySelectorAll('[data-reader-element]')].find(x => x.dataset.readerElement === target.elementId)
        if (!el) throw new Error('Missing stable element')
        return el
    }
    if (target.kind !== 'TEXT') throw new Error('Unsupported content anchor')
    return rawRange(doc,target.offset)
}
function glyphRange(doc,offset) {
    const range=rawRange(doc,offset)
    if (range.startContainer.nodeType === Node.TEXT_NODE) {
        const node=range.startContainer,at=range.startOffset
        if (at < node.length) range.setEnd(node,Math.min(at+(node.data.codePointAt(at)>0xffff ? 2 : 1),node.length))
        else if (at > 0) range.setStart(node,at-(node.data.charCodeAt(at-1)>=0xdc00 && node.data.charCodeAt(at-1)<=0xdfff ? 2 : 1))
    }
    return range
}
function normalizeTarget(doc,target) {
    if (target.kind !== 'TEXT') return target
    const index=textIndex(doc)
    // Pretty-printed books commonly begin with collapsed whitespace; hidden runs
    // are still canonical but are not an attainable visible reading position.
    let at=target.offset
    while(at<=index.length) {
        const point=index.text.codePointAt(at)
        if(at===target.offset || point!==undefined && !/\s/u.test(String.fromCodePoint(point))) {
            const rect=glyphRange(doc,at).getBoundingClientRect()
            if(rect.width>0.1 && rect.height>0.1)return {...target,offset:at,nearby:target.nearby || at!==target.offset}
        }
        if(point===undefined)break
        at += point>0xffff ? 2 : 1
    }
    for(let end=Math.min(target.offset,index.length);end>0;) {
        end -= index.text.charCodeAt(end-1)>=0xdc00 && index.text.charCodeAt(end-1)<=0xdfff ? 2 : 1
        if(end<0)break
        const point=index.text.codePointAt(end)
        if(!/\s/u.test(String.fromCodePoint(point))) {
            const rect=glyphRange(doc,end).getBoundingClientRect()
            if(rect.width>0.1 && rect.height>0.1)return {...target,offset:end,nearby:true}
        }
    }
    const el=doc.querySelector('[data-reader-element]')
    if(el)return {section:target.section,kind:'ELEMENT',elementId:el.dataset.readerElement,nearby:true}
    throw new Error('Chapter contains no visible text or element')
}
function visibleRect(doc,rect,node) {
    return sourceRectVisible(doc,rect,node,paginator.getBoundingClientRect())
}
function targetFor(range,index) {
    if (!range) return null
    const doc=range.startContainer.ownerDocument
    const visibleElement=[...doc.querySelectorAll('img[data-reader-element],svg[data-reader-element]')].find(el => visibleRect(doc,el.getBoundingClientRect(),el))
    if(visibleElement && (range.collapsed || textIndex(doc).length===0))return {section:index,kind:'ELEMENT',elementId:visibleElement.dataset.readerElement}
    const {starts,runs}=textIndex(doc)
    // Paginator ranges can begin on collapsed spaces on the preceding column.
    // Keep the canonical stream intact and select an actual glyph in this viewport.
    for(const run of runs) {
        if(run.node!==range.startContainer) {
            try { if(!range.intersectsNode(run.node))continue } catch {continue}
        }
        const begin=run.node===range.startContainer ? range.startOffset : 0
        const end=run.node===range.endContainer ? range.endOffset : run.node.length
        const runRange=doc.createRange();runRange.selectNodeContents(run.node)
        if(![...runRange.getClientRects()].some(rect=>visibleRect(doc,rect,run.node)))continue
        for(let at=begin;at<end;) {
            if(at>0 && run.node.data.charCodeAt(at)>=0xdc00 && run.node.data.charCodeAt(at)<=0xdfff && run.node.data.charCodeAt(at-1)>=0xd800 && run.node.data.charCodeAt(at-1)<=0xdbff)at--
            const offset=starts.get(run.node)+at
            if(!/\s/u.test(String.fromCodePoint(run.node.data.codePointAt(at))) && visibleRect(doc,glyphRange(doc,offset).getBoundingClientRect(),run.node))return {section:index,kind:'TEXT',offset}
            at+=run.node.data.codePointAt(at)>0xffff ? 2 : 1
        }
    }
    return visibleElement ? {section:index,kind:'ELEMENT',elementId:visibleElement.dataset.readerElement} : null
}
function readingViewport() {
    const rect=paginator.getBoundingClientRect(),visible=paginator.getContents().filter(x=>x.doc?.body&&x.doc.readyState==='complete'&&visibleFrame(x.doc,rect)).sort((a,b)=>a.index-b.index),ranges=[],elements=[],points=[]
    for(const {index,doc} of visible) { const offsets=textIndex(doc).starts;const values=textIntervals(doc,rect).filter(x=>offsets.has(x.node)).map(x=>({start:offsets.get(x.node)+x.start,end:offsets.get(x.node)+x.end}));for(const span of mergeIntervals(values))ranges.push({start:{section:index,kind:'TEXT',offset:span.start},end:{section:index,kind:'TEXT',offset:span.end}});for(const el of doc.querySelectorAll('[data-reader-element]'))if(visibleRect(doc,el.getBoundingClientRect(),el))elements.push({section:index,kind:'ELEMENT',elementId:el.dataset.readerElement}) }
    for(const {index,doc} of visible) { const value=textIndex(doc);if(!value.length)continue;const range=glyphRange(doc,value.length);range.collapse(false);if(visibleCaret(doc,range,rect))points.push({section:index,kind:'TEXT',offset:value.length}) }
    if(ranges.length>16384||elements.length>16384)throw new Error('Visible range exceeds bridge budget')
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
    return {base:paginationBase,signature:compactSignature([rect.width,rect.height,paginator.getAttribute('flow')]),currentSection:current,samples,ranges:ranges.length<=256?ranges:[],...(ranges.length>256?{rangeGroups:compactTextRanges(ranges)}:{}),elements:elements.length<=256?elements:[],...(elements.length>256?{elementGroups:compactElements(elements)}:{}),points,visiblePages:[],atEnd:Boolean(paginator.atEnd&&visible.some(x=>x.index===descriptorCount-1))}
}
async function validateCanonical(doc) {
    const value=textIndex(doc),expected=Number(doc.body.dataset.readerCanonicalLength)
    if(value.length!==expected)throw new Error(`Canonical DOM length mismatch: expected=${expected},actual=${value.length},url=${doc.URL},state=${doc.readyState},runs=${value.runs.length}`)
    const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value.text))
    const hex=[...new Uint8Array(digest)].map(x=>x.toString(16).padStart(2,'0')).join('')
    if(hex!==doc.body.dataset.readerCanonicalSha)throw new Error('Canonical DOM hash mismatch')
}
const validations=new WeakMap(), validatedDocuments=new WeakSet()
function anchorGeometry(target) {
    const entry = paginator.getContents().find(x => x.index === target?.section)
    if (!entry?.doc?.body) return {visible:false}
    try {
        const anchor = rangeFor(entry.doc,target)
        const range = target.kind==='TEXT' ? glyphRange(entry.doc,target.offset) : anchor.cloneRange?.()
        if (target.kind!=='TEXT' && range?.startContainer?.nodeType === Node.TEXT_NODE && range.startOffset < range.startContainer.length)
            range.setEnd(range.startContainer,range.startOffset+1)
        const rect = (range ?? anchor).getBoundingClientRect()
        const frame = entry.doc.defaultView.frameElement.getBoundingClientRect(), viewport = paginator.getBoundingClientRect()
        const left = rect.left+frame.left, top = rect.top+frame.top
        return {left,top,width:rect.width,height:rect.height,visible:sourceRectVisible(entry.doc,rect,(range??anchor).startContainer??anchor,viewport)}
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
        const visible=target ?? (relocationRange && targetFor(relocationRange.range,relocationRange.index))
        if(!visible || !anchorGeometry(visible).visible)return false
        const next=pageSignature(visible)
        equalFrames=next===signature ? equalFrames+1 : 0
        signature=next
        if(equalFrames>=2)return true
    }
    return false
}
function loadedPageDocument(doc) {
    return doc.readyState==='complete' && doc.body?.hasAttribute('data-reader-canonical-sha') && validatedDocuments.has(doc) &&
        doc.fonts.status==='loaded' && [...doc.images].every(image=>image.complete)
}
async function stable(target) {
    const deadline = performance.now() + 12000
    let signature, frames = 0, anchored = false
    while (!closed && performance.now() < deadline) {
        if (commandId !== latestRequestedId) throw superseded
        if (syncViewport()) anchored = false
        const contents = relevant(paginator.getContents(),target)
        if (!contents.length || contents.some(({doc}) => doc.readyState!=='complete' || !doc.body?.hasAttribute('data-reader-canonical-sha'))) {
            // A preloader exposes an about:blank view before its section's load event.
            await new Promise(resolve => setTimeout(resolve,50))
            continue
        }
        await bounded(Promise.all(contents.map(({doc}) => {
            let validation=validations.get(doc)
            if(!validation){validation=validateCanonical(doc).then(()=>validatedDocuments.add(doc));validations.set(doc,validation)}
            return validation
        })), 'canonical')
        // iframe load includes linked/flattened package CSS. Font faces are now
        // discoverable; fonts.ready alone before stylesheet load would be premature.
        await bounded(Promise.all(contents.map(({doc}) => doc.fonts.ready)), 'fonts')
        for(const {doc} of contents) {
            const failed=[...doc.fonts].filter(face=>face.status==='error')
            if(failed.length && !fontWarnings.has(doc))failedFontDocuments.add(doc)
        }
        await bounded(Promise.all(contents.flatMap(({doc}) => [...doc.images].map(settleImage))), 'images')
        if (target && !anchored && !anchorGeometry(target).visible) {
            const doc=contents.find(x=>x.index===target.section)?.doc
            if(!doc) { await new Promise(resolve=>setTimeout(resolve,50));continue }
            const normalized=normalizeTarget(doc,target)
            Object.assign(target,normalized)
            anchored = true
            // Re-anchor a loaded primary Range without re-running section navigation.
            // goTo on this same section also awaits an additional previous-section
            // preload; that spare view must not block settling the visible source.
            if(paginator.primaryIndex===target.section)
                await bounded(paginator.scrollToAnchor(rangeFor(doc,target)), 'anchorRange')
            else await bounded(paginator.goTo({ index: target.section, anchor: doc => rangeFor(doc, target) }), 'anchorGoTo')
        }
        await bounded(nextFrame(), 'layoutFrame')
        const now = relevant(paginator.getContents(),target)
        const next = pageSignature(target)
        frames = next === signature ? frames + 1 : 0
        signature = next
        if (frames >= 3 && now.every(({doc}) => doc.readyState==='complete' && doc.body?.hasAttribute('data-reader-canonical-sha') && validatedDocuments.has(doc) && doc.fonts.status === 'loaded') && (!target || anchorGeometry(target).visible)) return
    }
    throw new Error(`Reader layout did not stabilize: phase=${operationPhase},${contentState()},anchor=${JSON.stringify(anchorGeometry(target)).slice(0,180)}`)
}
function installListeners(instance) {
instance.addEventListener('load', ({ detail: { doc, index } }) => {
    if (instance !== paginator || closed) return
    installInput(doc,{active:()=>instance===paginator&&!opening&&!closed,flow:()=>paginator.getAttribute('flow'),direction:()=>inputDirection(doc),emit})
    // Content is inert even when it has same-origin DOM access for Range measurement.
    doc.addEventListener('click', event => {
        if(instance!==paginator || closed)return
        const link=event.target.closest?.('a')
        if(link) {
            event.preventDefault()
            if(!opening && event.isTrusted && link.dataset.readerExternal)emit('externalRequested',{section:index,href:link.dataset.readerExternal})
            else if(!opening && event.isTrusted && link.dataset.readerHref)emit('linkRequested',{section:index,href:link.dataset.readerHref})
            return
        }
        const image=event.target.closest?.('img[data-reader-element]')
        if(image && !opening && event.isTrusted && doc.getSelection()?.isCollapsed!==false) {
            event.preventDefault();emit('imageRequested',{section:index,elementId:image.dataset.readerElement})
        }
    }, true)
    let selectionTimer=0,lastSelectionAt=-Infinity
    const selection=()=>{
        if(opening || closed || instance!==paginator)return
        const selected=doc.getSelection();if(!selected || selected.isCollapsed || !selected.rangeCount){emit('selectionCleared');return}
        const range=selected.getRangeAt(0),value=textIndex(doc)
        const start=value.starts.get(range.startContainer),end=value.starts.get(range.endContainer)
        if(start===undefined || end===undefined)return
        const a=start+range.startOffset,b=end+range.endOffset
        if(b<=a || b-a>4096)return
        const rect=range.getBoundingClientRect()
        const text=selected.toString();if(!text || text.length>2000 || rect.width<=0 || rect.height<=0)return
        const pieces=[]
        for(const run of value.runs) {
            const from=Math.max(a,run.start),to=Math.min(b,run.end)
            if(to<=from || getComputedStyle(run.node.parentElement).visibility!=='visible')continue
            const piece=doc.createRange();piece.setStart(run.node,from-run.start);piece.setEnd(run.node,to-run.start)
            // Selection text excludes display:none runs, while canonical source retains them.
            if([...piece.getClientRects()].some(r=>r.width>0 && r.height>0))pieces.push({start:{section:index,offset:from},end:{section:index,offset:to}})
        }
        const group=compactTextRanges(pieces)[0];if(!group)return
        emit('selectionRequested',{section:index,start:a,end:b,text,selectionRanges:group.encoded,rect:{left:rect.left,top:rect.top,width:rect.width,height:rect.height}})
    }
    for(const name of ['pointerup','touchend','selectionchange'])doc.addEventListener(name,event=>{
        if(!event.isTrusted || opening || closed || instance!==paginator)return
        // Script-created selectionchange is trusted by browsers too. A real input
        // must precede it; direct pointer/touch completion remains independently trusted.
        if(name==='selectionchange' && performance.now()>userInteractionUntil)return
        clearTimeout(selectionTimer)
        selectionTimer=setTimeout(()=>{lastSelectionAt=performance.now();selection()},Math.max(0,200-(performance.now()-lastSelectionAt)))
    },{passive:true})
    for (const type of ['wheel','touchmove','pointerdown','keydown']) doc.addEventListener(type,event => {
        if (instance === paginator) userIntent(event)
    },{capture:true,passive:true})
    emit('sectionLoaded', { section: index, activeViews: paginator.getContents().length })
    // Neighbours do not gate an open, but every loaded document is still verified
    // against its canonical text in the background before it can be relied on.
    setTimeout(() => {
        if (instance !== paginator || closed || validations.has(doc) || doc.readyState !== 'complete' || !doc.body?.hasAttribute('data-reader-canonical-sha')) return
        const validation = validateCanonical(doc).then(() => validatedDocuments.add(doc))
        validations.set(doc, validation)
        validation.catch(error => { if (instance === paginator && !closed) emit('error', { message: String(error?.message ?? error).slice(0, 300) }) })
    }, 0)
})
instance.addEventListener('relocate', ({ detail }) => {
    if (instance !== paginator) return
    relocationDetail=detail
    relocationRange = {range:detail.range,index:detail.index}
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
    reportTimer = setTimeout(() => emit('relocated', { target, reason: 'scroll', intentCause:userIntentToken ? 'UserNavigation' : intentCause, trustedInput:Boolean(userIntentToken), userIntentToken }), 50)
})
instance.addEventListener('stabilized', () => { if (instance === paginator && !closed) stabilizedSequence++ })
}
installListeners(paginator)
installInput(document,{active:()=>!opening&&!closed,flow:()=>paginator.getAttribute('flow'),direction:()=>inputDirection(),emit})
function replacePaginator() {
    const previous = paginator
    const next = document.createElement('foliate-paginator')
    next.id = 'renderer'
    paginator = next // reject all late events from the previous engine before destroying it
    try { previous.destroy() } catch {}
    previous.replaceWith(next)
    installListeners(next)
    stabilizedSequence = 0
    relocationRange = undefined
}
async function execute(command) {
    if (closed || command.protocol !== 2 || command.sessionId !== identity?.sessionId || command.revision !== identity?.revision || command.commandId <= commandId || command.commandId !== latestRequestedId) return
    clearTimeout(reportTimer)
    opening = true
    userInteractionUntil = 0
    userIntentToken = ''
    intentToken = command.intentToken
    intentCause = command.intentCause
    if (!['UserNavigation','ProgrammaticJump','Reflow','Restore'].includes(intentCause) || typeof intentToken!=='string' || intentToken.length>64) return
    epoch = command.layoutEpoch
    commandId = command.commandId
    operationDeadline = performance.now() + 12000
    failedFontDocuments = new Set()
    try {
        const payload = command.payload
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
            bookDirection=payload.direction ?? bookDirection
            if(Number.isInteger(payload.sectionCount))descriptorCount=payload.sectionCount
            paginator.open({ dir: bookDirection, sections })
            paginator.setAttribute('flow', payload.flow)
            paginator.setStyles([payload.beforeAuthor, payload.afterAuthor])
            lastTarget = payload.target
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: doc => rangeFor(doc, lastTarget) }), 'navigationGoTo')
            await stable(lastTarget)
        } else if (command.type === 'goTo') {
            lastTarget = payload.target
            await bounded(paginator.goTo({ index: lastTarget.section, anchor: doc => rangeFor(doc, lastTarget) }), 'navigationGoTo')
            await stable(lastTarget)
        } else if (command.type === 'setRules') {
            lastTarget = payload.target
            for (const [key,value] of Object.entries(payload.geometry ?? {})) paginator.setAttribute(key,String(value))
            paginator.setAttribute('flow', payload.flow)
            paginator.setStyles([payload.beforeAuthor, payload.afterAuthor])
            await stable(lastTarget)
        } else if (command.type === 'next' || command.type === 'prev') {
            const noOp=command.type==='next'?paginator.atEnd:paginator.atStart
            // A box with min-height and a forced break (a title page, a volume page) can leave an empty column behind it.
            // Such a blank page has no glyph or image to anchor a position to: step over it (a few at most) instead of
            // failing; if nothing visible is found the previous position is kept and the blank page is simply shown.
            for(let blank=0;;blank++) {
                await bounded(paginator[command.type](), 'pageTurn')
                if(!await settleLoadedPage(noOp?lastTarget:undefined))await stable(noOp?lastTarget:undefined)
                if(noOp)break
                const visible=relocationRange && targetFor(relocationRange.range,relocationRange.index)
                if(visible) { relocationDraft=visible;lastTarget=visible;break }
                const edge=command.type==='next'?paginator.atEnd:paginator.atStart
                if(blank>=3 || edge)break
            }
        } else throw new Error('Unknown renderer command')
        if (commandId !== latestRequestedId || closed) throw superseded
        opening = false
        reveal()
        emit('ready', { profileId: currentProfile, activeViews: paginator.getContents().length, nearby:Boolean(lastTarget?.nearby) })
        if(failedFontDocuments.size) {
            for(const doc of failedFontDocuments)fontWarnings.add(doc)
            emit('notice',{message:'包内字体加载失败，已使用备用字体。'})
        }
        // For explicit anchoring, preserve the requested character within its visible page.
        // Normal page turns/scroll events continue to report first visible characters.
        emit('relocated', { target: lastTarget, reason: intentCause==='UserNavigation' ? 'page' : 'anchor', trustedInput:false, ack:commandId })
    } catch (error) { if (error !== superseded && !closed && commandId === latestRequestedId) { opening = false;reveal();emit('error', { message: String(error?.message ?? error).slice(0, 300) }) } }
}
globalThis.readerHost = Object.freeze({
    command(command) {
        if (command.protocol !== 2 || command.sessionId !== identity.sessionId || command.revision !== identity.revision || !Number.isInteger(command.commandId) || command.commandId <= latestRequestedId) return
        latestRequestedId = command.commandId
        chain = chain.then(() => execute(command))
    },
    close() { closed = true; clearTimeout(reportTimer); try { paginator.destroy() } catch {} },
    // Read-only diagnostics shared by device and desktop tests; no content/native privileges.
    diagnostics() {
        return { protocol: 2, identity, layoutEpoch: epoch, commandId, loaded: true, flow: paginator.getAttribute('flow'),
            operationPhase, primaryIndex:paginator.primaryIndex, contentState:contentState(), activeViews: paginator.getContents().length, target: lastTarget, actualVisibleTarget: relocationDraft, anchorGeometry:anchorGeometry(lastTarget), events: [...events],
            contents: paginator.getContents().map(({ index, doc }) => ({ section: index, blocks:doc.body ? textIndex(doc).runs.length : 0, canonicalHash:doc.body?.dataset.readerCanonicalSha, canonicalLength:doc.body?.dataset.readerCanonicalLength,
                indexedLength:canonical.get(doc)?.length ?? null,validated:validatedDocuments.has(doc),textLength: doc.body?.textContent.length ?? 0, url: doc.URL, state: doc.readyState, scriptExecuted: doc.defaultView?.attacked === true,
                failedFontFamilies:[...doc.fonts].filter(face=>face.status==='error').map(face=>face.family), fontSize: doc.body ? getComputedStyle(doc.body).fontSize : '', fontFamily: doc.body ? getComputedStyle(doc.body).fontFamily : '' })) }
    },
})
identity = { sessionId: base.pathname.split('/')[2], revision: decodeURIComponent(base.pathname.split('/')[3]) }
function viewportResized() { if (syncViewport() && !opening && !closed) emit('layoutInvalidated') }
addEventListener('resize',viewportResized)
globalThis.visualViewport?.addEventListener('resize',viewportResized)
emit('booted')
