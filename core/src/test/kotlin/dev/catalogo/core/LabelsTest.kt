package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val main = Device("p", "Samsung", "SH", account = "tienda", vintedId = "111")
private val other = Device("c1", "Poco", "SA", "s", style = 1, vintedId = "222")

private fun sale(
    order: String, detail: String = "Pago realizado correctamente", status: SaleStatus = SaleStatus.PENDING,
    number: Int = 6, code: String = "", soldAt: Long = 1_000, device: String = "p", id: String = "v$order",
    conflict: Boolean = false, title: String = "Pantalón",
) = Sale(
    number, device, 1_900, null, null, soldAt, id = id, status = status, order = order, detail = detail, code = code,
    conflict = conflict, title = title,
)

private fun shop(vararg sales: Sale) = ParentState(self = main, children = listOf(other), sales = sales.toList())

private fun reply(
    working: Boolean = false, lot: Long = 7, done: String = "null", waiting: String = "", entries: String = "",
) = Labels.parse(
    """{"modo":"${if (working) "trabajando" else "parado"}","esperando":"$waiting","lote":$lot,"pedidos":[$entries],"hecho":$done}""",
)

private fun done(lot: Long = 7, file: String = "etiquetas-1.pdf", labels: Int = 2, warnings: String = "") =
    """{"lote":$lot,"ts":5,"archivo":"$file","paginas":$labels,"etiquetas":$labels,"avisos":[$warnings]}"""

class LabelsTest {
    @Test
    fun `only the sales still to be sent can print a label`() {
        assertTrue(Labels.canPrint(sale("a")))
        assertTrue(Labels.canPrint(sale("a", detail = "")))
        assertTrue(Labels.canPrint(sale("a", detail = "Se ha enviado la etiqueta de envío")))
        for (gone in listOf(
            "El pedido se ha enviado y está de camino", "El pedido ya se puede recoger en la oficina postal",
            "Pedido entregado", "El comprador ha recibido el pedido", "Devolución en curso",
        )) assertEquals(false, Labels.canPrint(sale("a", detail = gone)), gone)
        assertEquals(false, Labels.canPrint(sale("a", status = SaleStatus.COMPLETED)))
        assertEquals(false, Labels.canPrint(sale("a", status = SaleStatus.CANCELLED)))
        assertEquals(false, Labels.canPrint(sale("")))
        assertEquals(false, Labels.canPrint(sale("a", conflict = true)))
    }

    @Test
    fun `asks for the oldest first with the code of each article`() {
        val s = shop(sale("b", soldAt = 2_000, number = 12, device = "c1"), sale("a", soldAt = 1_000, code = "SH0044"))
        assertEquals(
            listOf(Labels.Request("a", "SH0044", "Pantalón"), Labels.Request("b", "SA0012", "Pantalón")),
            Labels.requests(s),
        )
    }

    @Test
    fun `a bundle is one label with all its codes`() {
        val two = shop(sale("a", number = 6, id = "va-0"), sale("a", number = 12, id = "va-1"))
        assertEquals(listOf("SH0006-0012"), Labels.requests(two).map { it.code })
        val three = shop(sale("a", number = 6, id = "va-0"), sale("a", number = 12, id = "va-1"), sale("a", number = 31, id = "va-2"))
        assertEquals(listOf("SH0006#3"), Labels.requests(three).map { it.code })
        val mixed = shop(sale("a", number = 6, id = "va-0"), sale("a", number = 12, id = "va-1", device = "c1"))
        assertEquals(listOf("SH0006#2"), Labels.requests(mixed).map { it.code })
    }

    @Test
    fun `an unknown article asks without a code`() {
        assertEquals("", Labels.requests(shop(sale("a", number = -1))).single().code)
    }

    @Test
    fun `one sale can be asked for alone`() {
        val s = shop(sale("a"), sale("b", number = 7))
        assertEquals(listOf("b"), Labels.requests(s, only = "b").map { it.id })
    }

    @Test
    fun `reads what the PC answers`() {
        val r = reply(
            working = true, waiting = "se hará en cuanto dejes de usar el PC",
            entries = """{"id":"a","codigo":"SH0006","titulo":"x","estado":"lista","motivo":""},
                {"id":"b","codigo":"SH0007","titulo":"y","estado":"pendiente","motivo":""}""",
        )
        assertTrue(r.working)
        assertEquals(7, r.lot)
        assertEquals(1, r.left)
        assertNull(r.done)
        val d = reply(done = done(warnings = """"SH0007: sin recortar"""")).done!!
        assertEquals(Labels.Done(7, "etiquetas-1.pdf", 2, listOf("SH0007: sin recortar")), d)
        assertFailsWith<CatalogException> { Labels.parse("""{"error":"x"}""") }
        assertFailsWith<CatalogException> { Labels.parse("<html>") }
    }

    @Test
    fun `knows what to do with each answer`() {
        assertEquals(Labels.Step.Wait("Sacando etiquetas en el PC…"), Labels.step(7, reply(working = true)))
        assertEquals(
            Labels.Step.Wait("PC: el programa del PC está en pausa"),
            Labels.step(7, reply(working = true, waiting = "el programa del PC está en pausa")),
        )
        val pending = """{"id":"b","codigo":"SH0007","titulo":"y","estado":"pendiente","motivo":""}"""
        assertEquals(
            Labels.Step.Wait("Sacando etiquetas en el PC · queda 1"),
            Labels.step(7, reply(working = true, entries = pending)),
        )
        assertTrue(Labels.step(7, reply(done = done())) is Labels.Step.Fetch)
        assertEquals(
            Labels.Step.Failed(listOf("SH0007: tu dirección no aparece rellena")),
            Labels.step(7, reply(done = done(file = "", labels = 0, warnings = """"SH0007: tu dirección no aparece rellena""""))),
        )
        // Stopped on the PC before finishing, or another phone asked for something else.
        assertEquals(Labels.Step.Dropped, Labels.step(7, reply(done = done(lot = 3))))
        assertEquals(Labels.Step.Dropped, Labels.step(7, reply(working = true, lot = 9)))
    }

    @Test
    fun `takes the PDF and refuses anything else`() {
        val bytes = "%PDF-1.7 hola".toByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val pdf = Labels.parsePdf("""{"lote":7,"nombre":"etiquetas-1.pdf","datos":"$b64"}""")
        assertEquals(7, pdf.lot)
        assertEquals("etiquetas-1.pdf", pdf.name)
        assertContentEquals(bytes, pdf.bytes)
        val html = java.util.Base64.getEncoder().encodeToString("<html>".toByteArray())
        assertFailsWith<CatalogException> { Labels.parsePdf("""{"lote":7,"nombre":"a.pdf","datos":"$html"}""") }
        assertFailsWith<CatalogException> { Labels.parsePdf("""{"lote":7,"nombre":"a.pdf","datos":"%%%"}""") }
        assertFailsWith<CatalogException> { Labels.parsePdf("""{}""") }
    }

    @Test
    fun `the name it is saved with cannot leave the folder`() {
        val b64 = java.util.Base64.getEncoder().encodeToString("%PDF-1.7".toByteArray())
        val pdf = Labels.parsePdf("""{"lote":7,"nombre":"../../x y.exe","datos":"$b64"}""")
        assertEquals("x y.exe.pdf", pdf.name)
    }

    @Test
    fun `finds the printer app`() {
        val apps = listOf("com.google.drive" to "Drive", "com.xinye.barcode4" to "4Barcode", "a.b" to "4Barcode Pro")
        assertEquals(1, Labels.printer(apps))
        assertEquals(1, Labels.printer(listOf("a.pdf" to "Visor", "net.otro" to "4BarCode")))
        assertEquals(0, Labels.printer(listOf("com.xinye.barcode4.es" to "Etiquetas")))
        assertEquals(-1, Labels.printer(listOf("com.google.drive" to "Drive")))
    }
}
