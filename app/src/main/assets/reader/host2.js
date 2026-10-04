// Original host protocol. Only this trusted top-level document can call the native port.
import './vendor/foliate/paginator.js'
let paginator = document.getElementById('renderer'), initialized = false, publicationCount = 0
const base = new URL('./', location.href)
let identity, epoch = 0, commandId = 0, opening = true, closed = false, lastTarget, reportTimer
let chain = Promise.resolve()
let currentProfile = '', intentToken = '', intentCause = 'Restore', bookDirection='ltr', userIntentToken=''
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
    const id = commandId
    operationPhase = phase
    return new Promise((resolve,reject) => {
        const timer = setInterval(() => {
            if (closed || id !== latestRequestedId) { clearInterval(timer); reject(superseded) }
            else if (performance.now() >= operationDeadline) { clearInterval(timer); reject(new Error(`Reader operation timed out: phase=${phase},command=${commandId},${contentState()}`)) }
        }, 40)
        Promise.resolve(promise).then(value => {clearInterval(timer);resolve(value)},error => {clearInterval(timer);reject(error)})
    })
}
function contentState() {
    return paginator.getContents().map(({index,doc}) => `${index}:${doc.readyState}:${doc.body?.dataset.readerCanonicalLength ?? '-'}:${canonical.get(doc)?.length ?? '-'}`).join(';')
}
const events = []
function emit(type, payload = {}) {
    if (closed || !identity) return
    const event = { protocol: 2, ...identity, layoutEpoch: epoch, commandId, intentToken, intentCause, type, ...payload }
    if (events.length === 32) events.shift()
    events.push(event)
    globalThis.ReaderEvents?.postMessage(JSON.stringify(event))
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
function visibleRect(doc,rect) {
    const frame=doc.defaultView.frameElement.getBoundingClientRect(),viewport=paginator.getBoundingClientRect()
    return rect.width>0.1 && rect.height>0.1 && rect.top+frame.top<viewport.bottom && rect.bottom+frame.top>viewport.top && rect.left+frame.left<viewport.right && rect.right+frame.left>viewport.left
}
function targetFor(range,index) {
    if (!range) return null
    const doc=range.startContainer.ownerDocument
    const visibleElement=[...doc.querySelectorAll('img[data-reader-element],svg[data-reader-element]')].find(el => visibleRect(doc,el.getBoundingClientRect()))
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
        if(![...runRange.getClientRects()].some(rect=>visibleRect(doc,rect)))continue
        for(let at=begin;at<end;) {
            if(at>0 && run.node.data.charCodeAt(at)>=0xdc00 && run.node.data.charCodeAt(at)<=0xdfff && run.node.data.charCodeAt(at-1)>=0xd800 && run.node.data.charCodeAt(at-1)<=0xdbff)at--
            const offset=starts.get(run.node)+at
            if(!/\s/u.test(String.fromCodePoint(run.node.data.codePointAt(at))) && visibleRect(doc,glyphRange(doc,offset).getBoundingClientRect()))return {section:index,kind:'TEXT',offset}
            at+=run.node.data.codePointAt(at)>0xffff ? 2 : 1
        }
    }
    return visibleElement ? {section:index,kind:'ELEMENT',elementId:visibleElement.dataset.readerElement} : null
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
        return {left,top,width:rect.width,height:rect.height,visible:rect.width>0.1 && rect.height>0.1 && left+rect.width>viewport.left && left<viewport.right && top+rect.height>viewport.top && top<viewport.bottom}
    } catch {return {visible:false}}
}
async function stable(target) {
    const deadline = performance.now() + 12000
    let signature, frames = 0, anchored = false
    while (!closed && performance.now() < deadline) {
        if (commandId !== latestRequestedId) throw superseded
        if (syncViewport()) anchored = false
        const contents = paginator.getContents()
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
        const listeners = new AbortController()
        try {
            await bounded(Promise.all(contents.flatMap(({doc}) => [...doc.images].map(image => image.complete ? Promise.resolve() :
                new Promise((resolve,reject) => {
                    image.addEventListener('load',resolve,{once:true,signal:listeners.signal})
                    image.addEventListener('error',()=>reject(new Error('Article image failed to load')),{once:true,signal:listeners.signal})
                })))), 'images')
        } finally { listeners.abort() }
        if (contents.some(({doc}) => [...doc.images].some(image => !image.naturalWidth))) throw new Error('Article image failed to load')
        if (target && !anchored) {
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
        await new Promise(resolve => setTimeout(resolve, 50))
        const now = paginator.getContents()
        const next = JSON.stringify([stabilizedSequence, now.map(({index,doc}) => [index,doc.fonts.status,doc.body?.scrollWidth,doc.body?.scrollHeight]),target ? anchorGeometry(target) : null])
        frames = next === signature ? frames + 1 : 0
        signature = next
        if (frames >= 3 && now.every(({doc}) => doc.readyState==='complete' && doc.body?.hasAttribute('data-reader-canonical-sha') && validatedDocuments.has(doc) && doc.fonts.status === 'loaded') && (!target || anchorGeometry(target).visible)) return
    }
    throw new Error(`Reader layout did not stabilize: phase=${operationPhase},${contentState()},anchor=${JSON.stringify(anchorGeometry(target)).slice(0,180)}`)
}
function installListeners(instance) {
instance.addEventListener('load', ({ detail: { doc, index } }) => {
    if (instance !== paginator || closed) return
    // Content is inert even when it has same-origin DOM access for Range measurement.
    doc.addEventListener('click', event => {
        const link=event.target.closest?.('a')
        if(link) {
            event.preventDefault()
            if(!opening && event.isTrusted && link.dataset.readerExternal)emit('externalRequested',{href:link.dataset.readerExternal})
            else if(!opening && event.isTrusted && link.dataset.readerHref)emit('linkRequested',{href:link.dataset.readerHref})
        }
    }, true)
    for (const type of ['wheel','touchmove','pointerdown','keydown']) doc.addEventListener(type,event => {
        if (instance === paginator) userIntent(event)
    },{capture:true,passive:true})
    emit('sectionLoaded', { section: index, activeViews: paginator.getContents().length })
})
instance.addEventListener('relocate', ({ detail }) => {
    if (instance !== paginator) return
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
            bookDirection=payload.direction ?? bookDirection
            paginator.open({ dir: bookDirection === 'rtl' ? 'rtl' : 'ltr', sections })
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
            await bounded(paginator[command.type](), 'pageTurn')
            await stable()
            const visible=relocationRange && targetFor(relocationRange.range,relocationRange.index)
            if(!visible)throw new Error('Page has no visible reading position')
            relocationDraft=visible;lastTarget=visible
        } else throw new Error('Unknown renderer command')
        if (commandId !== latestRequestedId || closed) throw superseded
        opening = false
        emit('ready', { profileId: currentProfile, activeViews: paginator.getContents().length, nearby:Boolean(lastTarget?.nearby) })
        if(failedFontDocuments.size) {
            for(const doc of failedFontDocuments)fontWarnings.add(doc)
            emit('notice',{message:'包内字体加载失败，已使用备用字体。'})
        }
        // For explicit anchoring, preserve the requested character within its visible page.
        // Normal page turns/scroll events continue to report first visible characters.
        emit('relocated', { target: lastTarget, reason: intentCause==='UserNavigation' ? 'page' : 'anchor', trustedInput:false, ack:commandId })
    } catch (error) { if (error !== superseded && !closed && commandId === latestRequestedId) { opening = false;emit('error', { message: String(error?.message ?? error).slice(0, 300) }) } }
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
