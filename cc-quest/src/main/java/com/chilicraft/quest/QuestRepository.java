package com.chilicraft.quest;
import com.chilicraft.api.*; import java.util.*; import java.util.concurrent.*; import org.bukkit.Bukkit; import org.bukkit.plugin.java.JavaPlugin;
final class QuestRepository {
 private final JavaPlugin plugin; private final RowStore store; QuestRepository(JavaPlugin p,ChiliCraftAPI a){plugin=p;store=a.rowStore();}
 void load(UUID id, java.util.function.Consumer<List<Map<String,Object>>> done, java.util.function.Consumer<Throwable> fail){store.select("cc_quest_progress","player_id = ?",id).whenComplete((r,e)->Bukkit.getScheduler().runTask(plugin,()->{if(e!=null)fail.accept(e);else done.accept(r);}));}
 void save(UUID id,String c,String q,QuestState s,int progress,Integer completed){Map<String,Object> row=new LinkedHashMap<>();row.put("player_id",id);row.put("chapter_id",c);row.put("quest_id",q);row.put("state",s.name());row.put("progress",progress);row.put("completed_at",completed);row.put("updated_at",System.currentTimeMillis());store.upsert("cc_quest_progress",row,"player_id","chapter_id","quest_id");}
 void reset(UUID id, Runnable done){store.delete("cc_quest_progress","player_id = ?",id).whenComplete((r,e)->Bukkit.getScheduler().runTask(plugin,done));}
}
