package si.safeer.tv.cast

import org.json.JSONObject
import java.util.UUID

/** Universal Handoff v1. Izrecen prenos uporabniske seje na izbrano zaupanja vredno napravo. */
object SafeerHandoff {
    const val TYPE = "handoff.request"
    fun sporocilo(cilj:String, url:String, naslov:String="", polozaj:Double=0.0):JSONObject? {
        if(cilj.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        return JSONObject().put("id",UUID.randomUUID().toString()).put("type",TYPE).put("target",cilj).put("payload",JSONObject()
            .put("surface","media").put("url",url.take(2048)).put("title",naslov.take(200)).put("position",polozaj.coerceAtLeast(0.0)).put("created",System.currentTimeMillis()))
    }
    fun payload(msg:JSONObject):JSONObject? {
        if(msg.optString("type")!=TYPE)return null
        val p=msg.optJSONObject("payload")?:return null; val u=p.optString("url")
        if(!(u.startsWith("https://")||u.startsWith("http://")))return null
        return JSONObject().put("surface",p.optString("surface","media").take(32)).put("url",u.take(2048))
            .put("title",p.optString("title").take(200)).put("position",p.optDouble("position",0.0).coerceAtLeast(0.0))
    }
}
