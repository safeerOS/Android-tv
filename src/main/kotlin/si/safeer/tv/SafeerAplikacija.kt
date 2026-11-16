package si.safeer.tv

import android.app.Application
import si.safeer.tv.cast.LinkVticniki

/** Zagon procesa: izpolni vticnike skupne kode Link Core z izvedbami, ki obstajajo samo na televizorju. */
class SafeerAplikacija : Application() {
    override fun onCreate() {
        super.onCreate()
        LinkVticniki.jeRele = { gostitelj, vrata -> si.safeer.tv.link.GlobalLink.jeRele(gostitelj, vrata) }
        LinkVticniki.prekHuba = { metoda, pot, glave, vhod, izhod, poizvedba ->
            si.safeer.tv.link.DatotekeStreznik.prekHuba(metoda, pot, glave, vhod, izhod, poizvedba)
        }
        LinkVticniki.obUmikuPred = listOf(
            { app -> si.safeer.tv.link.DeljenjeZaslonaStoritev.ustaviBrezDostopa(app as android.content.Context) },
            { app -> si.safeer.tv.link.ZaupanjeZaslona.pocisti(app as android.content.Context) },
        )
        LinkVticniki.obUmikuPo = listOf({ app -> si.safeer.tv.link.VnosVklop.uskladi(app as android.content.Context, "krog") })
    }
}
