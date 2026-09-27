package si.safeer.tv.cast

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Safeer Continuity v3: isti delovni prostor, vec neodvisnih aktivnosti in naprav. */
class SafeerContinuity(context: Context, private val send: (JSONObject) -> Boolean) {
    companion object {
        const val CATEGORY = "safeer.continuity.v3"
        private val OLD = setOf("safeer.continuity.v2", "safeer.continuity.v1")
        private const val PREFS = "safeer_continuity_v3"
        private const val KEY_VERSION = "version"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 160
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun requestLatest(): Boolean = send(JSONObject().apply {
        put("id", UUID.randomUUID().toString()); put("type", "sync.request")
        put("payload", JSONObject().put("category", CATEGORY).put("since_version", prefs.getLong(KEY_VERSION, 0)))
    })

    fun publish(state: JSONObject): Boolean {
        val clean = sanitize(state); val aid = activityId(clean)
        if (aid.isBlank()) return false
        val now = System.currentTimeMillis(); clean.put("activity_id", aid).put("updated_at", maxOf(clean.optLong("updated_at",0), now))
        val items=loadItems(); val old=items.optJSONObject(aid)
        if (old == null || clean.optLong("updated_at") >= old.optLong("updated_at")) items.put(aid,clean)
        trim(items); val version=maxOf(now,prefs.getLong(KEY_VERSION,0)+1)
        prefs.edit().putLong(KEY_VERSION,version).putString(KEY_ITEMS,items.toString()).apply()
        return send(JSONObject().apply { put("id",UUID.randomUUID().toString()); put("type","sync.data"); put("target","all")
            put("payload",JSONObject().put("category",CATEGORY).put("version",version).put("timestamp",now/1000.0).put("data",JSONObject().put("items",items))) })
    }

    fun browser(url:String,title:String="",extra:JSONObject=JSONObject()) = publish(copy(extra).put("kind","browser").put("surface","browser").put("url",url).put("title",title).put("resource_id",url))
    fun search(query:String,extra:JSONObject=JSONObject()) = publish(copy(extra).put("kind","search").put("surface","search").put("query",query).put("resource_id",query))
    fun file(fileId:String,fileName:String="",extra:JSONObject=JSONObject()) = publish(copy(extra).put("kind","file").put("surface","files").put("file_id",fileId).put("file_name",fileName).put("resource_id",fileId))
    fun remoteApp(appId:String,title:String="",extra:JSONObject=JSONObject()) = publish(copy(extra).put("kind","remote_app").put("surface","remote_app").put("app_id",appId).put("app_title",title).put("resource_id",appId))

    /** Vedno pasivno: nikoli ne odpre strani, datoteke, aplikacije ali medija. */
    fun accept(payload:JSONObject):JSONObject? {
        val cat=payload.optString("category"); if (cat!=CATEGORY && cat !in OLD) return null
        val version=payload.optLong("version",0); val data=payload.optJSONObject("data")?:return null
        val incoming=data.optJSONObject("items")?:JSONObject().apply { put(activityId(data),data) }; val items=loadItems()
        incoming.keys().forEach { k -> val raw=incoming.optJSONObject(k)?:return@forEach; val clean=sanitize(raw); val aid=activityId(clean); if(aid.isBlank())return@forEach
            clean.put("activity_id",aid); val old=items.optJSONObject(aid); val at=clean.optLong("updated_at",version)
            if(old==null || at>old.optLong("updated_at",0)) items.put(aid,clean.put("updated_at",at)) }
        trim(items); prefs.edit().putLong(KEY_VERSION,maxOf(version,prefs.getLong(KEY_VERSION,0))).putString(KEY_ITEMS,items.toString()).apply()
        return JSONObject().put("items",items).put("passive",true)
    }

    fun resumeFor(state:JSONObject):JSONObject? { val aid=activityId(sanitize(state)); return if(aid.isBlank()) null else loadItems().optJSONObject(aid) }
    fun current():JSONObject=JSONObject().put("items",loadItems()).put("passive",true)
    private fun loadItems()=try{JSONObject(prefs.getString(KEY_ITEMS,"{}")?:"{}")}catch(_:Throwable){JSONObject()}
    private fun copy(o:JSONObject)=JSONObject(o.toString())
    private fun resourceKey(s:JSONObject):String { val kind=s.optString("kind",s.optString("surface","unknown")); val id=arrayOf("resource_id","media_id","file_id","app_id","url","query","title").map{s.optString(it)}.firstOrNull{it.isNotBlank()}?:""; return if(id.isBlank())"" else "$kind|$id".take(2300) }
    private fun activityId(s:JSONObject):String { s.optString("activity_id").takeIf{it.isNotBlank()}?.let{return it.take(128)}; val key=resourceKey(s); if(key.isBlank())return ""; val d=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()); return "a-"+d.take(12).joinToString(""){"%02x".format(it)} }
    private fun trim(items:JSONObject){ if(items.length()<=MAX_ITEMS)return; items.keys().asSequence().mapNotNull{k->items.optJSONObject(k)?.let{k to it.optLong("updated_at",0)}}.sortedByDescending{it.second}.drop(MAX_ITEMS).forEach{items.remove(it.first)} }
    private fun sanitize(input:JSONObject)=JSONObject().apply { arrayOf("activity_id","kind","surface","resource_id","url","title","query","media_id","media_title","media_position","media_duration","media_playing","file_id","file_name","file_position","app_id","app_title","app_context","cursor","scroll","selection","workspace_id","updated_by","updated_at").forEach{k-> if(input.has(k)&&!input.isNull(k)){ if(k!="url"||input.optString(k).startsWith("http://")||input.optString(k).startsWith("https://")) put(k,input.get(k)) }} }
}
