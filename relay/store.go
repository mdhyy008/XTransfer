package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

type Room struct {
	ID        string
	Code      string
	Name      string
	Creator   string
	CreatedAt int64
}

type FileInfo struct {
	ID   string
	Name string
	Size int64
	Mime string
}

const schema = `
CREATE TABLE IF NOT EXISTS rooms (
  id         TEXT PRIMARY KEY,
  code       TEXT NOT NULL DEFAULT '',
  name       TEXT NOT NULL,
  creator    TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rooms_code ON rooms(code) WHERE code <> '';

CREATE TABLE IF NOT EXISTS members (
  room_id   TEXT NOT NULL,
  device_id TEXT NOT NULL,
  name      TEXT NOT NULL DEFAULT '',
  platform  TEXT NOT NULL DEFAULT '',
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (room_id, device_id)
);

CREATE TABLE IF NOT EXISTS messages (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  id          TEXT NOT NULL UNIQUE,
  room_id     TEXT NOT NULL,
  from_id     TEXT NOT NULL,
  from_name   TEXT NOT NULL DEFAULT '',
  kind        TEXT NOT NULL,
  body        TEXT NOT NULL DEFAULT '',
  file_id     TEXT NOT NULL DEFAULT '',
  file_name   TEXT NOT NULL DEFAULT '',
  file_size   INTEGER NOT NULL DEFAULT 0,
  file_mime   TEXT NOT NULL DEFAULT '',
  recalled    INTEGER NOT NULL DEFAULT 0,
  recalled_by TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_room ON messages(room_id, seq);

CREATE TABLE IF NOT EXISTS files (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  size       INTEGER NOT NULL,
  mime       TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
`

func now() int64 {
	return time.Now().Unix()
}

func randomID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return hex.EncodeToString(buf)
}

func randomCode() string {
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	n := int64(buf[0])<<24 | int64(buf[1])<<16 | int64(buf[2])<<8 | int64(buf[3])
	return strconv.FormatInt(100000+n%900000, 10)
}

func OpenStore(path string) (*Store, error) {
	dsn := fmt.Sprintf(
		"file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)",
		filepath.ToSlash(path),
	)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// 单连接写库，避免 SQLite 并发写互相卡住；这个规模的中继够用。
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{db: db}
	if _, err := db.Exec(
		`INSERT OR IGNORE INTO rooms (id, code, name, creator, created_at) VALUES (?, '', ?, '', ?)`,
		publicRoomID, publicName, now(),
	); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func scanRoom(row *sql.Row) (*Room, error) {
	var r Room
	err := row.Scan(&r.ID, &r.Code, &r.Name, &r.Creator, &r.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &r, nil
}

func (s *Store) RoomByID(id string) (*Room, error) {
	if id == "" {
		return nil, nil
	}
	return scanRoom(s.db.QueryRow(
		`SELECT id, code, name, creator, created_at FROM rooms WHERE id = ?`, id,
	))
}

func (s *Store) RoomByCode(code string) (*Room, error) {
	if code == "" {
		return nil, nil
	}
	return scanRoom(s.db.QueryRow(
		`SELECT id, code, name, creator, created_at FROM rooms WHERE code = ?`, code,
	))
}

// CreateRoom 生成六位房间号，撞号就换一个重试。
func (s *Store) CreateRoom(name, creator string) (*Room, error) {
	for i := 0; i < 60; i++ {
		id := randomID()
		code := randomCode()
		_, err := s.db.Exec(
			`INSERT INTO rooms (id, code, name, creator, created_at) VALUES (?, ?, ?, ?, ?)`,
			id, code, name, creator, now(),
		)
		if err == nil {
			return &Room{ID: id, Code: code, Name: name, Creator: creator, CreatedAt: now()}, nil
		}
		// 只有唯一约束冲突才继续重试。
		if !isUnique(err) {
			return nil, err
		}
	}
	return nil, errors.New("生成房间号失败，请重试")
}

func isUnique(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "UNIQUE constraint failed")
}

func (s *Store) RoomsFor(deviceID string) ([]Room, error) {
	rows, err := s.db.Query(
		`SELECT id, code, name, creator, created_at FROM rooms
		 WHERE id = ? OR id IN (SELECT room_id FROM members WHERE device_id = ?)
		 ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, created_at ASC`,
		publicRoomID, deviceID, publicRoomID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Room
	for rows.Next() {
		var r Room
		if err := rows.Scan(&r.ID, &r.Code, &r.Name, &r.Creator, &r.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) MemberCount(roomID string) int {
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM members WHERE room_id = ?`, roomID).Scan(&n)
	return n
}

func (s *Store) MemberIDs(roomID string) ([]string, error) {
	rows, err := s.db.Query(`SELECT device_id FROM members WHERE room_id = ?`, roomID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func (s *Store) Members(roomID string) ([]memberView, error) {
	rows, err := s.db.Query(
		`SELECT device_id, name, platform, joined_at FROM members WHERE room_id = ? ORDER BY joined_at ASC`,
		roomID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []memberView
	for rows.Next() {
		var m memberView
		if err := rows.Scan(&m.DeviceID, &m.Name, &m.Platform, &m.JoinedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) IsMember(roomID, deviceID string) bool {
	if roomID == publicRoomID {
		return true
	}
	var one int
	err := s.db.QueryRow(
		`SELECT 1 FROM members WHERE room_id = ? AND device_id = ?`, roomID, deviceID,
	).Scan(&one)
	return err == nil
}

func (s *Store) AddMember(roomID, deviceID, name, platform string) error {
	_, err := s.db.Exec(
		`INSERT INTO members (room_id, device_id, name, platform, joined_at) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT(room_id, device_id) DO UPDATE SET
		   name = excluded.name,
		   platform = excluded.platform`,
		roomID, deviceID, name, platform, now(),
	)
	return err
}

func (s *Store) RemoveMember(roomID, deviceID string) error {
	_, err := s.db.Exec(
		`DELETE FROM members WHERE room_id = ? AND device_id = ?`, roomID, deviceID,
	)
	return err
}

// UpdateProfile 设备改名后，把它在全部房间里的名字一起换掉。
func (s *Store) UpdateProfile(deviceID, name, platform string) error {
	_, err := s.db.Exec(
		`UPDATE members SET name = ?, platform = ? WHERE device_id = ?`,
		name, platform, deviceID,
	)
	return err
}

func (s *Store) RenameRoom(roomID, name string) error {
	_, err := s.db.Exec(`UPDATE rooms SET name = ? WHERE id = ?`, name, roomID)
	return err
}

// InsertMessage 用客户端给的 id 去重：同一条消息重发不会多出一条。
func (s *Store) InsertMessage(m *messageView) (int64, bool, error) {
	res, err := s.db.Exec(
		`INSERT INTO messages (id, room_id, from_id, from_name, kind, body,
		   file_id, file_name, file_size, file_mime, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO NOTHING`,
		m.ID, m.Room, m.From, m.FromName, m.Kind, m.Body,
		m.FileID, m.FileName, m.FileSize, m.FileMime, m.CreatedAt,
	)
	if err != nil {
		return 0, false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, false, err
	}
	if n == 0 {
		var seq int64
		if err := s.db.QueryRow(`SELECT seq FROM messages WHERE id = ?`, m.ID).Scan(&seq); err != nil {
			return 0, false, err
		}
		return seq, false, nil
	}
	seq, err := res.LastInsertId()
	if err != nil {
		return 0, false, err
	}
	return seq, true, nil
}

func (s *Store) History(roomID string, beforeSeq int64, limit int) ([]messageView, bool, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	upper := beforeSeq
	if upper <= 0 {
		upper = math.MaxInt64
	}
	rows, err := s.db.Query(
		`SELECT id, room_id, from_id, from_name, kind, body, file_id, file_name,
		   file_size, file_mime, recalled, seq, created_at
		 FROM messages WHERE room_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?`,
		roomID, upper, limit+1,
	)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	var out []messageView
	for rows.Next() {
		var m messageView
		var recalled int
		if err := rows.Scan(
			&m.ID, &m.Room, &m.From, &m.FromName, &m.Kind, &m.Body, &m.FileID,
			&m.FileName, &m.FileSize, &m.FileMime, &recalled, &m.Seq, &m.CreatedAt,
		); err != nil {
			return nil, false, err
		}
		m.Recalled = recalled != 0
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	hasMore := len(out) > limit
	if hasMore {
		out = out[:limit]
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, hasMore, nil
}

func (s *Store) RecallBy(roomID, messageID, by string) (string, bool, error) {
	var fromID string
	err := s.db.QueryRow(
		`SELECT from_id FROM messages WHERE id = ? AND room_id = ?`, messageID, roomID,
	).Scan(&fromID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	_, err = s.db.Exec(
		`UPDATE messages SET recalled = 1, recalled_by = ? WHERE id = ?`, by, messageID,
	)
	return fromID, true, err
}

func (s *Store) SaveFile(info FileInfo) error {
	_, err := s.db.Exec(
		`INSERT INTO files (id, name, size, mime, created_at) VALUES (?, ?, ?, ?, ?)`,
		info.ID, info.Name, info.Size, info.Mime, now(),
	)
	return err
}

func (s *Store) File(id string) (*FileInfo, error) {
	var info FileInfo
	err := s.db.QueryRow(
		`SELECT id, name, size, mime FROM files WHERE id = ?`, id,
	).Scan(&info.ID, &info.Name, &info.Size, &info.Mime)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &info, nil
}
