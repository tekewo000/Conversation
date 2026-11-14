package conversation

import "base:runtime"
import "core:encoding/json"
import "core:fmt"
import "core:strings"

EPISODE_SCHEMA :: `
CREATE TABLE IF NOT EXISTS episode_nodes(memory_id INTEGER PRIMARY KEY REFERENCES memories(id),root_key TEXT NOT NULL,parent_key TEXT NOT NULL,node_key TEXT NOT NULL,node TEXT NOT NULL CHECK(json_valid(node) AND json_type(node)='object'));
CREATE INDEX IF NOT EXISTS episode_parent ON episode_nodes(root_key,parent_key,node_key,memory_id);
CREATE TRIGGER IF NOT EXISTS episode_no_update BEFORE UPDATE ON episode_nodes BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS episode_no_delete BEFORE DELETE ON episode_nodes BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

// An extraction sends changed branches; complete descendants live independently of the 30 root summaries.
episode_collect :: proc(entries:^json.Array, value:json.Value, root_key, parent_key, scope:string, depth:int, seen:^map[string]bool) -> bool {
 node,ok:=value.(json.Object)
 if !ok || depth>3 || len(entries^)>=128 do return fail("Expected at most four episode levels and 128 changed nodes.")
 key,key_ok:=string_field(node,"key",128 if depth==0 else 96)
 node_scope,scope_ok:=string_field(node,"scope",16)
 if !key_ok || !scope_ok || node_scope!=scope do return fail("Episode children must keep their root scope and a stable key.")
 identity:=strings.concatenate({root_key,"\x00",key})
 defer delete(identity)
 if seen^[identity] do return fail("Duplicate episode node key within an experience.")
 seen^[strings.clone(identity)]=true
 for field in node { if field!="key" && field!="text" && field!="emotion" && field!="scene" && field!="scope" && field!="source_ids" && field!="children" do return fail("Unknown episode node field.") }
 scene,scene_ok:=node["scene"].(json.String)
 if !scene_ok || len(scene)>(768 if depth==0 else 256) do return fail("Episode scene labels exceed their byte budget.")
 if !episode_parent_valid(root_key,key,parent_key,scope) do return false

 append(entries,value)
 if children_value,exists:=node["children"]; exists {
  children,children_ok:=children_value.(json.Array)
  if !children_ok do return fail("Episode children must be an array.")
  for child in children { if !episode_collect(entries,child,root_key,key,scope,depth+1,seen) do return false }
 }
 return true
}

episode_parent_valid :: proc(root_key,key,parent_key,scope:string) -> bool {
 check:=prepare("SELECT count(*) FROM episode_nodes n JOIN memories m ON m.id=n.memory_id WHERE n.root_key=?1 AND n.node_key=?2 AND m.scope=?3 AND n.parent_key!=?4")
 if check==nil do return false
 valid:=bind_text(check,1,root_key) && bind_text(check,2,key) && bind_text(check,3,scope) && bind_text(check,4,parent_key) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==0
 sqlite3_finalize(check)
 if !valid do return fail("An existing episode node cannot change its parent.")
 return true
}

episode_compact :: proc(state:string) -> cstring {
 return json_query(`SELECT json_set(?1,'$.episodes',json(coalesce((SELECT json_group_array(json_remove(value,'$.children')) FROM json_each(?1,'$.episodes')),'[]')))`,state)
}

episode_save_link :: proc(memory_id:i64, root_key,parent_key:string, episode:json.Object) -> bool {
 exists:=prepare("SELECT 1 FROM episode_nodes WHERE memory_id=?1")
 if exists==nil do return false
 found:=bind_int(exists,1,memory_id) && sqlite3_step(exists)==100
 sqlite3_finalize(exists)
 if found do return true
 encoded,e:=json.marshal(episode)
 defer delete(encoded)
 if e!=nil do return false
 node:=json_query(`SELECT json_remove(?1,'$.children')`,string(encoded))
 if node==nil do return false
 stmt:=prepare("INSERT OR IGNORE INTO episode_nodes(memory_id,root_key,parent_key,node_key,node) VALUES(?1,?2,?3,?4,?5)")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,memory_id) && bind_text(stmt,2,root_key) && bind_text(stmt,3,parent_key) && bind_text(stmt,4,string(episode["key"].(json.String))) && bind_text(stmt,5,string(node)) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !saved do return false
 return archive_capture("episode_nodes",memory_id)
}

episode_archive_link_valid :: proc(row:json.Object) -> bool {
 value,parsed:=parse_json(transmute([]byte)string(row["node"].(json.String)))
 if !parsed do return false
 defer json.destroy_value(value)
 node,ok:=value.(json.Object)
 if !ok || len(node)!=6 do return false
 key,key_ok:=string_field(node,"key",128)
 text,text_ok:=string_field(node,"text",1536)
 scope,scope_ok:=string_field(node,"scope",16)
 emotion,emotion_ok:=node["emotion"].(json.String)
 scene,scene_ok:=node["scene"].(json.String)
 sources,sources_ok:=node["source_ids"].(json.Array)
 if !key_ok || !text_ok || !scope_ok || !emotion_ok || !scene_ok || !sources_ok || len(sources)<1 || len(sources)>8 || len(emotion)>768 || len(scene)>768 do return false
 root:=string(row["root_key"].(json.String))
 parent:=string(row["parent_key"].(json.String))
 if len(root)>128 || len(parent)>128 || key!=string(row["node_key"].(json.String)) || (parent=="" && root!=key) || (parent!="" && (root==key || parent==key || len(key)>96)) do return false
 if !episode_parent_valid(root,key,parent,scope) do return false
 memory_id:=i64(row["memory_id"].(json.Integer))
 memory_key:=fmt.aprintf("episode:%s",key) if parent=="" else fmt.aprintf("episode-node:%d:%s:%s",len(root),root,key)
 defer delete(memory_key)
 check:=prepare("SELECT count(*) FROM memories WHERE id=?1 AND actor='conversation' AND subject='episode' AND kind='event' AND scope=?2 AND key=?3 AND instr(text,?4)>0 AND instr(text,?5)>0 AND instr(text,?6)>0")
 if check==nil do return false
 valid:=bind_int(check,1,memory_id) && bind_text(check,2,scope) && bind_text(check,3,memory_key) && bind_text(check,4,text) && bind_text(check,5,string(emotion)) && bind_text(check,6,string(scene)) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
 sqlite3_finalize(check)
 if !valid do return false
 for source,i in sources {
  id,id_ok:=source.(json.Integer)
  if !id_ok || id<1 do return false
  for previous in sources[:i] { if previous.(json.Integer)==id do return false }
  stmt:=prepare("SELECT 1 FROM memory_sources WHERE memory_id=?1 AND message_id=?2")
  if stmt==nil do return false
  found:=bind_int(stmt,1,memory_id) && bind_int(stmt,2,i64(id)) && sqlite3_step(stmt)==100
  sqlite3_finalize(stmt)
  if !found do return false
 }
 if parent!="" {
  stmt:=prepare("WITH RECURSIVE chain(node_key,parent_key,depth) AS (SELECT n.node_key,n.parent_key,1 FROM episode_nodes n JOIN memories m ON m.id=n.memory_id JOIN memory_current current ON current.id=n.memory_id WHERE n.root_key=?1 AND n.node_key=?2 AND m.scope=?3 UNION ALL SELECT n.node_key,n.parent_key,c.depth+1 FROM chain c JOIN episode_nodes n ON n.root_key=?1 AND n.node_key=c.parent_key JOIN memories m ON m.id=n.memory_id JOIN memory_current current ON current.id=n.memory_id WHERE c.parent_key!='' AND c.depth<3 AND m.scope=?3) SELECT 1 FROM chain WHERE node_key=?1 AND parent_key='' LIMIT 1")
  if stmt==nil do return false
  valid=bind_text(stmt,1,root) && bind_text(stmt,2,parent) && bind_text(stmt,3,scope) && sqlite3_step(stmt)==100
  sqlite3_finalize(stmt)
 }
 return valid
}

EPISODE_HIERARCHY_JSON :: `coalesce((WITH RECURSIVE ancestors(memory_id,root_key,parent_key,node_key,node,depth) AS (
 SELECT n.memory_id,n.root_key,n.parent_key,n.node_key,n.node,0 FROM episode_nodes n WHERE n.memory_id=m.id
 UNION ALL SELECT n.memory_id,n.root_key,n.parent_key,n.node_key,n.node,a.depth+1 FROM ancestors a JOIN episode_nodes n ON n.root_key=a.root_key AND n.node_key=a.parent_key JOIN memory_current c ON c.id=n.memory_id JOIN memories p ON p.id=n.memory_id WHERE a.parent_key!='' AND a.depth<3 AND p.scope=m.scope
) SELECT json_group_array(json_object('memory_id',memory_id,'root_key',root_key,'parent_key',parent_key,'key',node_key,'scene',json_extract(node,'$.scene'),'text',substr(json_extract(node,'$.text'),1,256),'source_ids',json((SELECT json_group_array(message_id) FROM (SELECT message_id FROM memory_sources WHERE memory_id=a.memory_id ORDER BY message_id DESC LIMIT 8))))) FROM (SELECT * FROM ancestors ORDER BY depth DESC) a),'[]')`

@(export)
conversation_episode_children :: proc "c" (request:cstring) -> cstring {
 context=runtime.default_context()
 if !ready || request==nil || len(string(request))>1024 { fail("Invalid episode request."); return nil }
 value,ok:=parse_json(transmute([]byte)string(request))
 if !ok do return nil
 defer json.destroy_value(value)
 obj,obj_ok:=value.(json.Object)
 _,root_ok:=string_field(obj,"root_key",128)
 _,parent_ok:=string_field(obj,"parent_key",128)
 scope,scope_ok:=string_field(obj,"scope",16)
 after,after_ok:=obj["after_id"].(json.Integer)
 if !obj_ok || len(obj)!=4 || !root_ok || !parent_ok || !scope_ok || (scope!="real" && scope!="fiction" && scope!="shared") || !after_ok || after<0 { fail("Invalid episode request fields."); return nil }
 return json_query(`WITH rows AS MATERIALIZED (
 SELECT n.*,m.scope FROM episode_nodes n JOIN memory_current c ON c.id=n.memory_id JOIN memories m ON m.id=n.memory_id
 WHERE n.root_key=json_extract(?1,'$.root_key') AND n.parent_key=json_extract(?1,'$.parent_key') AND m.scope=json_extract(?1,'$.scope') AND n.memory_id>json_extract(?1,'$.after_id') ORDER BY n.memory_id LIMIT 65
), page AS (SELECT * FROM rows LIMIT 64)
SELECT json_object('episodes',json((SELECT json_group_array(json_set(node,'$.source_ids',json((SELECT json_group_array(message_id) FROM (SELECT message_id FROM memory_sources WHERE memory_id=page.memory_id ORDER BY message_id DESC LIMIT 8))),'$.has_children',json(CASE WHEN EXISTS(SELECT 1 FROM episode_nodes child JOIN memory_current c ON c.id=child.memory_id JOIN memories cm ON cm.id=child.memory_id WHERE child.root_key=page.root_key AND child.parent_key=page.node_key AND cm.scope=page.scope) THEN 'true' ELSE 'false' END))) FROM page)),
'after_id',coalesce((SELECT max(memory_id) FROM page),json_extract(?1,'$.after_id')),'has_more',json(CASE WHEN (SELECT count(*) FROM rows)>64 THEN 'true' ELSE 'false' END))`,string(request))
}
