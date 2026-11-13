/*
 * ROM de prueba del cable link (modo multijugador de la GBA).
 * Toda la pantalla toma un color según lo que presiona LA OTRA consola:
 *   gris  = sin datos por el cable
 *   azul  = enlazadas, la otra no presiona nada
 *   verde = la otra presiona A
 *   rojo  = la otra presiona B
 */
typedef unsigned short u16;
#define REG16(a) (*(volatile u16*) (a))
#define DISPCNT REG16(0x04000000)
#define VCOUNT REG16(0x04000006)
#define SIOMULTI ((volatile u16*) 0x04000120)
#define SIOCNT REG16(0x04000128)
#define SIOMLT_SEND REG16(0x0400012A)
#define KEYINPUT REG16(0x04000130)
#define RCNT REG16(0x04000134)
#define PALETTE ((volatile u16*) 0x05000000)

int main(void) {
	DISPCNT = 0;          /* modo 0 sin capas: se ve el color de fondo (paleta 0) */
	RCNT = 0;             /* puerto en modo SIO */
	SIOCNT = 0x2003;      /* multijugador, 115200 bps */
	PALETTE[0] = 0x4210;
	for (;;) {
		while (VCOUNT >= 160) {}
		while (VCOUNT < 160) {}
		SIOMLT_SEND = (~KEYINPUT) & 0x3FF;
		u16 cnt = SIOCNT;
		if (!(cnt & 0x4) && !(cnt & 0x80)) {
			/* Somos la consola maestra: iniciar transferencia y esperar a que termine. */
			SIOCNT = cnt | 0x80;
			int t;
			for (t = 0; t < 100000 && (SIOCNT & 0x80); ++t) {}
		}
		u16 id = (SIOCNT >> 4) & 3;
		u16 other = SIOMULTI[id ? 0 : 1];
		u16 color;
		if (other == 0xFFFF) {
			color = 0x4210;
		} else if (other & 1) {
			color = 0x03E0;
		} else if (other & 2) {
			color = 0x001F;
		} else {
			color = 0x7C00;
		}
		PALETTE[0] = color;
	}
}
