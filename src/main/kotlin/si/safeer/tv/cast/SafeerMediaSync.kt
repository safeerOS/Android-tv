package si.safeer.tv.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Safeer Media Sync v1.1: per-item merge. Brisanja so tombstones, zato stara naprava ne ozivi izbrisanega vnosa. */
class SafeerMediaSync(context: Context, private val send: (JSONObject) -> Boolean) {
    companion object { const val CATEGORY="safeer.media.v1"; private const val PREF="safeer_media_sync"; private const val VER="version"; private const val STATE="state"; private const val TOMBSTONE=2592000000L }
    private val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun requestLatest()=send(JSONObject().put("id",UUID.randomUUID().toString()).put("type","sync.request").put("payload",JSONObject().put("category",CATEGORY).put("since_version",prefs.getLong(VER,0))))
    fun publish(state:JSONObject):Boolean { val now=System.currentTimeMillis(); val merged=merge(raw(),state,now); val v=maxOf(now,prefs.getLong(VER,0)+1); save(v,merged); return send(JSONObject().put("id",UUID.randomUUID().toString()).put("type","sync.data").put("target","all").put("payload",JSONObject().put("category",CATEGORY).put("version",v).put("timestamp",now/1000.0).put("data",merged))) }
    fun accept(payload:JSONObject):JSONObject? { if(payload.optString("category")!=CATEGORY)return null; val d=payload.optJSONObject("data")?:return null; val before=raw().toString(); val merged=merge(raw(),d,System.currentTimeMillis()); val v=maxOf(prefs.getLong(VER,0),payload.optLong("version")); save(v,merged); return if(merged.toString()!=before) visible(merged) else null }
    fun current()=visible(raw())
    private fun raw()=try{JSONObject(prefs.getString(STATE,"{}")?:"{}")}catch(_:Throwable){JSONObject()}
    private fun save(v:Long,s:JSONObject){prefs.edit().putLong(VER,v).putString(STATE,s.toString()).apply()}
    internal fun merge(a:JSONObject,b:JSONObject,now:Long):JSONObject { val aa=clean(a,true); val bb=clean(b,true); return JSONObject().apply { arrayOf("sources","favorites","progress").forEach { name -> val key=if(name=="sources")"url" else "id"; val m=linkedMapOf<String,JSONObject>(); fun add(ar:JSONArray){for(i in 0 until ar.length()){val x=ar.optJSONObject(i)?:continue;val k=x.optString(key);val old=m[k];if(k.isNotBlank()&&(old==null||x.optLong("updated")>old.optLong("updated")))m[k]=x}};add(aa.optJSONArray(name)?:JSONArray());add(bb.optJSONArray(name)?:JSONArray());val lim=if(name=="sources")80 else if(name=="favorites")300 else 30;val rows=m.values.filter{!(it.optBoolean("deleted")&&now-it.optLong("updated")>TOMBSTONE)}.sortedByDescending{it.optLong("updated")}.take(lim);put(name,JSONArray(rows)) }; put("updated",maxOf(now,aa.optLong("updated"),bb.optLong("updated"))) } }
    private fun visible(x:JSONObject)=clean(x,false)
    private fun clean(d:JSONObject, tomb:Boolean)=JSONObject().apply { val base=d.optLong("updated",System.currentTimeMillis()); arrayOf("sources","favorites","progress").forEach{name->val a=d.optJSONArray(name);val out=JSONArray();if(a!=null)for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val id=if(name=="sources")x.optString("url") else x.optString("id");if(id.isBlank())continue;val updated=x.optLong("updated",base);if(x.optBoolean("deleted")){if(tomb)out.put(JSONObject().put(if(name=="sources")"url" else "id",id).put("updated",updated).put("deleted",true));continue};if(name=="sources"){if(http(id))out.put(JSONObject().put("type",x.optString("type").take(24)).put("name",x.optString("name").take(120)).put("url",id.take(2048)).put("updated",updated))}else if(name=="favorites"){val u=x.optString("url");if(http(u))out.put(JSONObject().put("id",id.take(300)).put("title",x.optString("title").take(200)).put("artist",x.optString("artist").take(160)).put("url",u.take(2048)).put("video",x.optBoolean("video")).put("updated",updated))}else{val p=x.optDouble("position",0.0).coerceAtLeast(0.0);val du=x.optDouble("duration",0.0).coerceAtLeast(0.0);if(du>0&&p<du*.95)out.put(JSONObject().put("id",id.take(300)).put("title",x.optString("title").take(200)).put("position",p).put("duration",du).put("updated",updated))else if(tomb)out.put(JSONObject().put("id",id.take(300)).put("updated",updated).put("deleted",true))}};put(name,out)};put("updated",base)}
    private fun http(s:String)=s.startsWith("https://")||s.startsWith("http://")
}
