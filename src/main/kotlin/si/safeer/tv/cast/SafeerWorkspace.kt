package si.safeer.tv.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Passive shared work-context catalog. Never opens/focuses remote activities. */
class SafeerWorkspace(context: Context, private val send:(JSONObject)->Boolean) {
    companion object { const val CATEGORY="safeer.workspace.v1"; private const val MAX=24; private const val MAX_ACT=24 }
    private val prefs=context.getSharedPreferences("safeer_workspace_v1",Context.MODE_PRIVATE)
    private fun load()=try{JSONObject(prefs.getString("items","{}")?:"{}")}catch(_:Throwable){JSONObject()}
    fun begin(title:String=""):String { val id="w-"+UUID.randomUUID().toString().replace("-","").take(24); upsert(JSONObject().put("workspace_id",id).put("title",title).put("activity_ids",JSONArray())); return id }
    fun attach(workspaceId:String, activityId:String, title:String?=null):Boolean { if(workspaceId.isBlank()||activityId.isBlank())return false; val all=load(); val x=all.optJSONObject(workspaceId)?:JSONObject().put("workspace_id",workspaceId).put("title",title?:"").put("activity_ids",JSONArray()); if(title!=null)x.put("title",title); val old=x.optJSONArray("activity_ids")?:JSONArray(); val ids=mutableListOf<String>(); for(i in 0 until old.length()) old.optString(i).takeIf{it.isNotBlank()}?.let{if(it !in ids)ids.add(it)}; if(activityId !in ids)ids.add(activityId); x.put("activity_ids",JSONArray(ids.takeLast(MAX_ACT))); return upsert(x) }
    fun upsert(raw:JSONObject):Boolean { val id=raw.optString("workspace_id").take(128); if(id.isBlank())return false; val now=System.currentTimeMillis(); val all=load(); val clean=JSONObject().put("workspace_id",id).put("title",raw.optString("title").take(160)).put("activity_ids",raw.optJSONArray("activity_ids")?:JSONArray()).put("archived",raw.optBoolean("archived",false)).put("updated_at",maxOf(now,raw.optLong("updated_at",0))); all.put(id,clean); trim(all); prefs.edit().putString("items",all.toString()).apply(); return send(JSONObject().put("id",UUID.randomUUID().toString()).put("type","sync.data").put("target","all").put("payload",JSONObject().put("category",CATEGORY).put("version",now).put("data",JSONObject().put("items",all)))) }
    fun accept(payload:JSONObject):JSONObject? { if(payload.optString("category")!=CATEGORY)return null; val incoming=payload.optJSONObject("data")?.optJSONObject("items")?:return null; val all=load(); incoming.keys().forEach{k->val x=incoming.optJSONObject(k)?:return@forEach; val id=x.optString("workspace_id").take(128); if(id.isBlank())return@forEach; val old=all.optJSONObject(id); if(old==null||x.optLong("updated_at")>old.optLong("updated_at"))all.put(id,x)}; trim(all); prefs.edit().putString("items",all.toString()).apply(); return JSONObject().put("workspaces",all).put("passive",true) }
    private fun trim(all:JSONObject){if(all.length()<=MAX)return; all.keys().asSequence().mapNotNull{k->all.optJSONObject(k)?.let{k to it.optLong("updated_at")}}.sortedByDescending{it.second}.drop(MAX).forEach{all.remove(it.first)}}
}
