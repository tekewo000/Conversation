package conversation

when ODIN_OS == .Windows {
 foreign import sqlite "../.tools/sqlite/windows/sqlite3.lib"
} else {
 foreign import sqlite "system:sqlite3"
}
SQLite :: distinct rawptr
Statement :: distinct rawptr
foreign sqlite {
 sqlite3_open_v2 :: proc "c" (path: cstring, db: ^SQLite, flags: i32, vfs: cstring) -> i32 ---
 sqlite3_close :: proc "c" (db: SQLite) -> i32 ---
 sqlite3_errmsg :: proc "c" (db: SQLite) -> cstring ---
 sqlite3_busy_timeout :: proc "c" (db: SQLite, ms: i32) -> i32 ---
 sqlite3_exec :: proc "c" (db: SQLite, sql: cstring, callback: rawptr, data: rawptr, error: rawptr) -> i32 ---
 sqlite3_prepare_v2 :: proc "c" (db: SQLite, sql: cstring, size: i32, stmt: ^Statement, tail: rawptr) -> i32 ---
 sqlite3_finalize :: proc "c" (stmt: Statement) -> i32 ---
 sqlite3_step :: proc "c" (stmt: Statement) -> i32 ---
 sqlite3_stmt_status :: proc "c" (stmt: Statement, operation, reset: i32) -> i32 ---
 sqlite3_bind_text :: proc "c" (stmt: Statement, index: i32, text: rawptr, bytes: i32, destroy: rawptr) -> i32 ---
 sqlite3_bind_int64 :: proc "c" (stmt: Statement, index: i32, value: i64) -> i32 ---
 sqlite3_column_text :: proc "c" (stmt: Statement, index: i32) -> cstring ---
 sqlite3_column_int64 :: proc "c" (stmt: Statement, index: i32) -> i64 ---
 sqlite3_last_insert_rowid :: proc "c" (db: SQLite) -> i64 ---
}
