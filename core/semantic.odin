package conversation

import "base:runtime"
import "core:encoding/json"
import "core:fmt"

@(export)
conversation_context_semantic :: proc "c" (query, candidates: cstring) -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 if candidates==nil || len(string(candidates))>2048 { fail("Invalid semantic candidates."); return nil }
 root,ok:=parse_json(transmute([]byte)string(candidates),2)
 if !ok do return nil
 defer json.destroy_value(root)
 ids,array_ok:=root.(json.Array)
 if !array_ok || len(ids)>48 { fail("Expected at most 48 semantic memory IDs."); return nil }
 for value,i in ids {
  id,valid:=value.(json.Integer)
  if !valid || id<=0 { fail("Invalid semantic memory ID."); return nil }
  for previous in ids[:i] { if previous.(json.Integer)==id { fail("Duplicate semantic memory ID."); return nil } }
 }
 text,match,valid:=search_arguments(query)
 if !valid do return nil
 defer delete(match)
 return json_query(CONTEXT_JSON,text,match,candidates)
}

@(export)
conversation_embedding_batch :: proc "c" (after_id: i64) -> cstring {
 context=runtime.default_context()
 if !ready || after_id<0 { fail("Invalid memory embedding cursor."); return nil }
 cursor:=fmt.aprintf("%d",after_id)
 defer delete(cursor)
 return json_query(`WITH batch AS MATERIALIZED (
 SELECT m.id,m.supersedes_id,m.kind,m.actor,m.subject,m.scope,substr(m.text,1,4096) AS text,EXISTS(SELECT 1 FROM memory_current c WHERE c.id=m.id) AS current FROM memories m WHERE m.id>CAST(?1 AS INTEGER) ORDER BY m.id LIMIT 16
 ) SELECT json_object('through_id',coalesce((SELECT max(id) FROM batch),CAST(?1 AS INTEGER)),
 'items',json((SELECT json_group_array(json_object('id',id,'supersedes_id',coalesce(supersedes_id,0),'kind',kind,'actor',actor,'subject',subject,'scope',scope,'text',text,'current',json(CASE WHEN current THEN 'true' ELSE 'false' END))) FROM batch)),
 'has_more',json(CASE WHEN EXISTS(SELECT 1 FROM memories WHERE id>coalesce((SELECT max(id) FROM batch),CAST(?1 AS INTEGER))) THEN 'true' ELSE 'false' END))`,cursor)
}
