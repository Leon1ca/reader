// Original trusted geometry adapter. Text is measured in source DOM code units.
export function textIntervals(doc, viewport) {
    const frame=doc.defaultView.frameElement.getBoundingClientRect(),ranges=[]
    if(frame.width<=.1||frame.height<=.1||frame.bottom<=viewport.top||frame.top>=viewport.bottom||frame.right<=viewport.left||frame.left>=viewport.right)return ranges
    const local={left:viewport.left-frame.left,right:viewport.right-frame.left,top:viewport.top-frame.top,bottom:viewport.bottom-frame.top}
    const intersects=r=>r.width>.1&&r.height>.1&&r.right>local.left&&r.left<local.right&&r.bottom>local.top&&r.top<local.bottom
    const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT)
    for(let node;(node=walker.nextNode());) {
        if(!node.length)continue
        const vertical=/^(vertical|sideways)/.test(getComputedStyle(node.parentElement).writingMode)
        const whole=doc.createRange();whole.selectNodeContents(node)
        // Ruby caret hit-testing can select the base or the second annotation
        // character at either edge. Short runs are measured character by
        // character, including annotations, without filling invisible gaps.
        if(node.length<=256) {
            if(![...whole.getClientRects()].some(intersects))continue
            let start=null,end=0
            for(let at=0;at<node.length;){const next=at+(node.data.codePointAt(at)>0xffff?2:1),glyph=doc.createRange();glyph.setStart(node,at);glyph.setEnd(node,next)
                if([...glyph.getClientRects()].some(intersects)){if(start===null)start=at;end=next}else if(start!==null){ranges.push({node,start,end});start=null}
                at=next
            }
            if(start!==null)ranges.push({node,start,end})
            if(ranges.length>16384)throw new Error('Visible text range exceeds geometry budget')
            continue
        }
        for(const rect of whole.getClientRects()) {
            if(!intersects(rect))continue
            let first=node.length,last=0
            const left=Math.max(local.left,rect.left),right=Math.min(local.right,rect.right),top=Math.max(local.top,rect.top),bottom=Math.min(local.bottom,rect.bottom)
            const points=vertical ? [[left+(right-left)/2,top+Math.min(.5,(bottom-top)/4)],[left+(right-left)/2,bottom-Math.min(.5,(bottom-top)/4)]] : [[left+Math.min(.5,(right-left)/4),top+(bottom-top)/2],[right-Math.min(.5,(right-left)/4),top+(bottom-top)/2]]
            for(const [x,y] of points) {
                const caret=doc.caretRangeFromPoint?.(x,y)
                const alternative=caret?null:doc.caretPositionFromPoint?.(x,y)
                if((caret?.startContainer??alternative?.offsetNode)!==node)continue
                const offset=caret?.startOffset??alternative?.offset
                for(let at of [offset-1,offset]) {
                    if(at<0||at>=node.length)continue
                    if(at>0&&node.data.charCodeAt(at)>=0xdc00&&node.data.charCodeAt(at)<=0xdfff&&node.data.charCodeAt(at-1)>=0xd800&&node.data.charCodeAt(at-1)<=0xdbff)at--
                    const end=at+(node.data.codePointAt(at)>0xffff?2:1),glyph=doc.createRange();glyph.setStart(node,at);glyph.setEnd(node,end)
                    const box=glyph.getBoundingClientRect()
                    if(intersects(box)&&box.right>rect.left+.01&&box.left<rect.right-.01&&box.bottom>rect.top+.01&&box.top<rect.bottom-.01){first=Math.min(first,at);last=Math.max(last,end)}
                }
            }
            if(first<last)ranges.push({node,start:first,end:last})
            if(ranges.length>16384)throw new Error('Visible text range exceeds geometry budget')
        }
    }
    return ranges
}
export function mergeIntervals(values) {
    values.sort((a,b)=>a.start-b.start||a.end-b.end)
    const merged=[]
    for(const value of values){const last=merged.at(-1);if(last&&value.start<=last.end)last.end=Math.max(last.end,value.end);else merged.push({...value})}
    if(merged.length>16384)throw new Error('Visible source range exceeds geometry budget')
    return merged
}
// Preserve every gap. A dense ruby line can contain hundreds of disjoint source
// intervals; delta encoding avoids repeating two full anchor objects per glyph.
export function compactTextRanges(ranges) {
    if(ranges.length>16384)throw new Error('Visible source range exceeds geometry budget')
    const groups=new Map(),result=[];let totalBytes=0
    for(const value of ranges){const section=value.start.section,blockId=value.start.blockId,key=JSON.stringify([section,blockId??null]),group=groups.get(key)??{section,blockId,values:[]};group.values.push({start:value.start.offset??value.start.character,end:value.end.offset??value.end.character});groups.set(key,group)}
    if(groups.size>256)throw new Error('Visible source group exceeds bridge budget')
    for(const {section,blockId,values} of groups.values()){let previous=0;const bytes=[]
        const write=value=>{do{const next=value%128;value=Math.floor(value/128);bytes.push(next|(value?128:0));if(++totalBytes>40960)throw new Error('Visible source range exceeds encoded budget')}while(value)}
        for(const value of mergeIntervals(values)){if(!Number.isSafeInteger(value.start)||!Number.isSafeInteger(value.end)||value.start<previous||value.end<=value.start||value.end>2147483647)throw new Error('Invalid visible source range');write(value.start-previous);write(value.end-value.start);previous=value.end}
        result.push({section,...(blockId===undefined?{}:{blockId}),encoded:btoa(bytes.map(x=>String.fromCharCode(x)).join(''))})
    }
    return result
}
export function compactElements(elements) {
    if(elements.length>16384)throw new Error('Visible element exceeds geometry budget')
    const groups=new Map();let encodedUnits=0
    for(const element of elements) {
        const id=element.elementId??element.blockId
        if(typeof id!=='string'||id.length<1||id.length>128)throw new Error('Invalid visible element')
        const ids=groups.get(element.section)??new Set();if(!ids.has(id)){encodedUnits+=JSON.stringify(id).length+1;if(encodedUnits>40960)throw new Error('Visible element exceeds encoded budget');ids.add(id)}groups.set(element.section,ids)
    }
    if(groups.size>8)throw new Error('Visible element group exceeds bridge budget')
    return [...groups].map(([section,ids])=>({section,ids:[...ids]}))
}
export function visibleFrame(doc,viewport) {
    const rect=doc.defaultView.frameElement.getBoundingClientRect()
    return rect.width>.1&&rect.height>.1&&rect.bottom>viewport.top&&rect.top<viewport.bottom&&rect.right>viewport.left&&rect.left<viewport.right
}
export function fontGeometry(docs) {
    return [...new Set(docs.map(doc=>{const style=getComputedStyle(doc.body);return JSON.stringify([style.writingMode,style.direction,[...doc.fonts].map(f=>[f.family,f.style,f.weight,f.status]).sort()])}))].sort()
}
export function compactSignature(value) {
    const text=JSON.stringify(value);let a=2166136261,b=5381
    for(let at=0;at<text.length;at++){a=Math.imul(a^text.charCodeAt(at),16777619);b=Math.imul(b,33)^text.charCodeAt(at)}
    return `${(a>>>0).toString(16)}-${(b>>>0).toString(16)}`
}

// A real caret can have zero width at an empty paragraph or inclusive EOF.
export function visibleCaret(doc,range,viewport) {
    const frame=doc.defaultView.frameElement.getBoundingClientRect()
    return [...range.getClientRects()].some(r=>r.height>.1&&r.left+frame.left>=viewport.left&&r.left+frame.left<viewport.right&&r.bottom+frame.top>viewport.top&&r.top+frame.top<viewport.bottom)
}
