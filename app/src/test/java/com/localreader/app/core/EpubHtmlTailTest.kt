package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class EpubHtmlTailTest {
    @Test fun whitespaceAfterBodyAndHtmlIsRemovedAndNothingElseChanges() {
        assertEquals("<html><body><p>a</p>\n  </body></html>",EpubHtmlTail.normalize("<html><body><p>a</p>\n  </body>\n</html>\n"))
        assertEquals("<html><body></body></html>",EpubHtmlTail.normalize("<html><body></body>\r\n\t </html>\r\n"))
        val minified = "<html><body><p>a</p></body></html>"
        assertSame("an already tight document is returned as is",minified,EpubHtmlTail.normalize(minified))
        assertEquals("text inside the body keeps its whitespace","<html><body>\n  x \n</body></html>",EpubHtmlTail.normalize("<html><body>\n  x \n</body>\n</html>\n"))
    }
    @Test fun anythingUnexpectedAfterTheBodyIsLeftAlone() {
        val odd = "<html><body>x</body><!-- c --></html>\n"
        assertSame(odd,EpubHtmlTail.normalize(odd))
        val noBody = "<html>\n</html>\n"
        assertSame(noBody,EpubHtmlTail.normalize(noBody))
        val textAfter = "<html><body>x</body>tail</html>\n"
        assertSame(textAfter,EpubHtmlTail.normalize(textAfter))
    }
}
