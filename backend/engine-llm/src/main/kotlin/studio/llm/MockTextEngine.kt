package studio.llm

import kotlinx.serialization.json.*
import studio.engine.*

/** Offline fixture for the editor and the shared queue. All responses are explicitly labelled Mock. */
class MockTextEngine : TextEngine {
    override val identity = "mock-writer@local"
    override val real = false
    override suspend fun info() = TextEngineInfo("mock","mock-writer-v1",true,version="mock-writer-v1")
    override suspend fun idle() = true
    override suspend fun advance(attempt: String, call: TextCall, allowStart: Boolean): TextRun {
        if (!allowStart) return TextRun.Unsent
        val req = call.input.getValue("request").jsonObject
        val idea = req.getValue("brief").jsonPrimitive.content
        fun count(key: String, default: Int) = req[key]?.jsonPrimitive?.intOrNull ?: default
        fun entity(key: String, name: String, description: String) = buildJsonObject {
            put("key",key); put("name",name); put("description",description)
        }
        val result = when (call.kind) {
            "OUTLINE" -> buildJsonObject {
                put("title","末班信 · Mock"); put("logline",idea.take(500)); put("world","山城旧邮局即将关门，最后一班夜车仍替邻里传递口信。")
                val cast = listOf(entity("lin","林青","接手旧邮局的年轻站员"),entity("he","何叔","即将退休的夜车司机"))
                val sites = listOf(entity("post","旧邮局","堆满退信的柜台"),entity("platform","站台","沿河的小站"),entity("bridge","石桥","通向山村的最后一段路"))
                put("characters",JsonArray(List(count("characterCount",2)) { cast.getOrElse(it) { n -> entity("c$n","旅客$n","夜车上的乘客") } }))
                put("locations",JsonArray(List(count("locationCount",3)) { sites.getOrElse(it) { n -> entity("l$n","沿途站点$n","沿河的小站") } }))
                put("beats",JsonArray(listOf("林青发现没有地址的信","何叔认出笔迹","两人赶在末班车前送信").map(::JsonPrimitive)))
            }
            "EPISODE_PLAN" -> buildJsonObject {
                put("episodes",buildJsonArray {
                    repeat(req.getValue("episodeCount").jsonPrimitive.int) { i -> addJsonObject {
                        put("title","第${i+1}封信"); put("summary","沿着线索寻找寄件人，第${i+1}段旅程带来新的选择。")
                        put("targetDurationMs",req.getValue("targetDurationMs"))
                    } }
                })
            }
            "SCRIPT" -> {
                val outline = call.input.getValue("outline").jsonObject
                fun firstKey(name: String) = outline.getValue(name).jsonArray.first().jsonObject.getValue("key")
                val episode = call.input.getValue("episode").jsonObject
                buildJsonObject {
                    put("title",episode.getValue("title"))
                    val scenes = count("sceneCount",1)
                    val duration = episode.getValue("targetDurationMs").jsonPrimitive.int
                    put("scenes",buildJsonArray { repeat(scenes) { i -> addJsonObject {
                        put("locationKey",firstKey("locations")); put("characters",JsonArray(listOf(firstKey("characters"))))
                        put("action","她走到窗前，慢慢展开那封信。"); put("durationMs",maxOf(1000,duration/scenes + if (i==0) duration%scenes else 0))
                        put("dialogues",buildJsonArray { repeat(count("linesPerScene",1)) { addJsonObject {
                            put("speaker",firstKey("characters")); put("text","这封信，还来得及送到。"); put("emotion","坚定")
                        } } })
                    } } })
                }
            }
            "ASSET_EXTRACT" -> buildJsonObject {
                put("scenes",buildJsonArray { for (scene in call.input.getValue("script").jsonObject.getValue("scenes").jsonArray) addJsonObject {
                    put("items",buildJsonArray { if ("信" in scene.jsonObject.getValue("action").jsonPrimitive.content) addJsonObject {
                        put("type","PROP"); put("name","信件"); put("description","场次中出现的信件 · Mock")
                    } })
                } })
            }
            "SHOT_PLAN" -> buildJsonObject {
                put("scenes",buildJsonArray { for (scene in call.input.getValue("script").jsonObject.getValue("scenes").jsonArray) addJsonObject {
                    put("shots",buildJsonArray { repeat(count("shotsPerScene",2)) { i -> addJsonObject {
                        put("framing",if(i==0) "全景" else "近景"); put("camera",if(i==0) "固定机位" else "缓推")
                        put("action",scene.jsonObject.getValue("action").jsonPrimitive.content.take(720)+" · Mock ${i+1}")
                    } } })
                } })
            }
            "SHOT_REGENERATE" -> buildJsonObject {
                put("framing","特写"); put("camera","平视缓推")
                put("action",(idea.ifBlank { call.input.getValue("shot").jsonObject.getValue("action").jsonPrimitive.content }).take(700)+" · Mock 重做")
            }
            else -> error("未知文字任务")
        }
        return TextRun.Completed(result.toString(),null)
    }
}
