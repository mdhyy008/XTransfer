package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
)

type Hub struct {
	store *Store

	mu      sync.RWMutex
	clients map[string]*client

	limMu sync.Mutex
	joins map[string][]time.Time
}

type client struct {
	hub      *Hub
	conn     *websocket.Conn
	deviceID string
	name     string
	platform string
	send     chan []byte
	done     chan struct{}
	once     sync.Once
}

func NewHub(store *Store) *Hub {
	return &Hub{
		store:   store,
		clients: make(map[string]*client),
		joins:   make(map[string][]time.Time),
	}
}

func (h *Hub) HandleWS(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	device := strings.TrimSpace(r.URL.Query().Get("device"))
	if device == "" || len(device) > 128 {
		http.Error(w, "invalid device", http.StatusBadRequest)
		return
	}
	name := truncate(strings.TrimSpace(r.URL.Query().Get("name")), 40)
	if name == "" {
		name = truncate(device, 24)
	}
	platform := truncate(strings.TrimSpace(r.URL.Query().Get("platform")), 16)

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		OriginPatterns: []string{"*"},
	})
	if err != nil {
		return
	}
	conn.SetReadLimit(256 * 1024)

	c := &client{
		hub:      h,
		conn:     conn,
		deviceID: device,
		name:     name,
		platform: platform,
		send:     make(chan []byte, 64),
		done:     make(chan struct{}),
	}

	if err := h.store.UpdateProfile(device, name, platform); err != nil {
		log.Printf("更新成员资料失败: %v", err)
	}

	old := h.register(c)
	if old != nil {
		old.close(websocket.StatusPolicyViolation, "replaced")
	}

	go c.writeLoop()
	c.sendJSON(map[string]any{
		"type":       "ready",
		"deviceId":   device,
		"rooms":      h.roomViews(device),
		"serverTime": now(),
	})
	h.readLoop(c)
}

func (h *Hub) register(c *client) *client {
	h.mu.Lock()
	defer h.mu.Unlock()
	old := h.clients[c.deviceID]
	h.clients[c.deviceID] = c
	return old
}

func (h *Hub) unregister(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[c.deviceID] == c {
		delete(h.clients, c.deviceID)
	}
}

func (h *Hub) onlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}

func (h *Hub) online(deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.clients[deviceID]
	return ok
}

func (h *Hub) clientOf(deviceID string) *client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.clients[deviceID]
}

func (h *Hub) readLoop(c *client) {
	defer h.unregister(c)
	defer c.closeNow()
	for {
		_, data, err := c.conn.Read(context.Background())
		if err != nil {
			return
		}
		var in wsIn
		if err := json.Unmarshal(data, &in); err != nil {
			c.sendError("消息格式错误")
			continue
		}
		h.handle(c, in)
	}
}

func (c *client) writeLoop() {
	ticker := time.NewTicker(45 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case data := <-c.send:
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := c.conn.Write(ctx, websocket.MessageText, data)
			cancel()
			if err != nil {
				c.closeNow()
				return
			}
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := c.conn.Ping(ctx)
			cancel()
			if err != nil {
				c.closeNow()
				return
			}
		case <-c.done:
			return
		}
	}
}

func (c *client) enqueue(data []byte) {
	select {
	case <-c.done:
		return
	default:
	}
	select {
	case c.send <- data:
	case <-c.done:
	case <-time.After(5 * time.Second):
		c.close(websocket.StatusPolicyViolation, "slow client")
	}
}

func (c *client) sendJSON(value any) {
	data, err := json.Marshal(value)
	if err != nil {
		return
	}
	c.enqueue(data)
}

func (c *client) sendError(message string) {
	c.sendJSON(map[string]any{"type": "error", "message": message})
}

func (c *client) close(code websocket.StatusCode, reason string) {
	c.once.Do(func() {
		close(c.done)
		_ = c.conn.Close(code, reason)
	})
}

func (c *client) closeNow() {
	c.once.Do(func() {
		close(c.done)
		_ = c.conn.CloseNow()
	})
}

func (h *Hub) handle(c *client, in wsIn) {
	switch strings.ToLower(in.Type) {
	case "create":
		h.createRoom(c, in)
	case "join":
		h.joinRoom(c, in)
	case "leave":
		h.leaveRoom(c, in)
	case "send":
		h.sendMessage(c, in)
	case "history":
		h.history(c, in)
	case "members":
		h.sendMembers(c, in.Room)
	case "recall":
		h.recall(c, in)
	case "kick":
		h.kick(c, in)
	case "rename":
		h.rename(c, in)
	case "ping":
		c.sendJSON(map[string]any{"type": "pong"})
	default:
		c.sendError("未知操作")
	}
}

func (h *Hub) createRoom(c *client, in wsIn) {
	name := roomName(in.Name)
	room, err := h.store.CreateRoom(name, c.deviceID)
	if err != nil {
		c.sendError(err.Error())
		return
	}
	if err := h.store.AddMember(room.ID, c.deviceID, c.name, c.platform); err != nil {
		c.sendError("创建房间失败")
		return
	}
	c.sendJSON(map[string]any{"type": "joined", "room": h.roomView(*room), "created": true})
}

func (h *Hub) joinRoom(c *client, in wsIn) {
	code := strings.TrimSpace(in.Code)
	if len(code) != 6 {
		c.sendError("房间号是六位数字")
		return
	}
	for _, r := range code {
		if r < '0' || r > '9' {
			c.sendError("房间号是六位数字")
			return
		}
	}
	if !h.joinAllowed(c.deviceID) {
		c.sendError("加入太频繁，稍后再试")
		return
	}
	room, err := h.store.RoomByCode(code)
	if err != nil {
		c.sendError("查询房间失败")
		return
	}
	if room == nil {
		c.sendError("房间不存在")
		return
	}
	if err := h.store.AddMember(room.ID, c.deviceID, c.name, c.platform); err != nil {
		c.sendError("加入房间失败")
		return
	}
	c.sendJSON(map[string]any{"type": "joined", "room": h.roomView(*room)})
	h.broadcastMembers(room.ID)
}

func (h *Hub) leaveRoom(c *client, in wsIn) {
	if in.Room == "" || in.Room == publicRoomID {
		c.sendError("不能退出公网广播")
		return
	}
	if err := h.store.RemoveMember(in.Room, c.deviceID); err != nil {
		c.sendError("退出房间失败")
		return
	}
	c.sendJSON(map[string]any{"type": "left", "room": in.Room})
	h.broadcastMembers(in.Room)
}

func (h *Hub) sendMessage(c *client, in wsIn) {
	room, err := h.store.RoomByID(in.Room)
	if err != nil {
		c.sendError("查询房间失败")
		return
	}
	if room == nil || !h.store.IsMember(room.ID, c.deviceID) {
		c.sendError("不在这个房间里")
		return
	}

	m := messageView{
		ID:        messageID(in.ID),
		Room:      room.ID,
		From:      c.deviceID,
		FromName:  c.name,
		CreatedAt: now(),
	}
	switch strings.ToLower(in.Kind) {
	case "", kindText:
		body := strings.TrimSpace(in.Body)
		if body == "" {
			c.sendError("消息不能为空")
			return
		}
		if utf8.RuneCountInString(body) > maxTextRunes {
			c.sendError("消息太长了")
			return
		}
		m.Kind = kindText
		m.Body = body
	case kindImage, kindFile:
		if in.FileID == "" {
			c.sendError("文件不存在")
			return
		}
		info, err := h.store.File(in.FileID)
		if err != nil || info == nil {
			c.sendError("文件不存在")
			return
		}
		m.Kind = strings.ToLower(in.Kind)
		m.FileID = info.ID
		m.FileName = info.Name
		m.FileSize = info.Size
		m.FileMime = info.Mime
	default:
		c.sendError("不支持的消息类型")
		return
	}

	seq, inserted, err := h.store.InsertMessage(&m)
	if err != nil {
		c.sendError("消息发送失败")
		return
	}
	c.sendJSON(map[string]any{"type": "ack", "id": m.ID, "seq": seq})
	if !inserted {
		return
	}
	m.Seq = seq
	h.broadcastRoom(room.ID, map[string]any{"type": "message", "message": m})
}

func (h *Hub) history(c *client, in wsIn) {
	room, err := h.store.RoomByID(in.Room)
	if err != nil || room == nil || !h.store.IsMember(room.ID, c.deviceID) {
		c.sendError("不在这个房间里")
		return
	}
	messages, hasMore, err := h.store.History(room.ID, in.BeforeSeq, in.Limit)
	if err != nil {
		c.sendError("读取历史失败")
		return
	}
	if messages == nil {
		messages = []messageView{}
	}
	c.sendJSON(map[string]any{
		"type":     "history",
		"room":     room.ID,
		"messages": messages,
		"hasMore":  hasMore,
	})
}

func (h *Hub) sendMembers(c *client, roomID string) {
	room, err := h.store.RoomByID(roomID)
	if err != nil || room == nil || !h.store.IsMember(room.ID, c.deviceID) {
		c.sendError("不在这个房间里")
		return
	}
	c.sendJSON(map[string]any{
		"type":    "members",
		"room":    room.ID,
		"members": h.memberViews(*room),
	})
}

func (h *Hub) recall(c *client, in wsIn) {
	room, err := h.store.RoomByID(in.Room)
	if err != nil || room == nil || !h.store.IsMember(room.ID, c.deviceID) {
		c.sendError("不在这个房间里")
		return
	}
	fromID, found, err := h.store.RecallBy(room.ID, in.ID, c.deviceID)
	if err != nil {
		c.sendError("撤回失败")
		return
	}
	if !found {
		c.sendError("消息不存在")
		return
	}
	if fromID != c.deviceID && room.Creator != c.deviceID {
		c.sendError("没有撤回权限")
		return
	}
	h.broadcastRoom(room.ID, map[string]any{
		"type": "recalled",
		"room": room.ID,
		"id":   in.ID,
		"by":   c.deviceID,
	})
}

func (h *Hub) kick(c *client, in wsIn) {
	room, err := h.store.RoomByID(in.Room)
	if err != nil || room == nil {
		c.sendError("房间不存在")
		return
	}
	if room.Creator != c.deviceID {
		c.sendError("没有管理权限")
		return
	}
	target := strings.TrimSpace(in.Target)
	if target == "" || target == room.Creator {
		c.sendError("不能移除这个成员")
		return
	}
	if err := h.store.RemoveMember(room.ID, target); err != nil {
		c.sendError("移除失败")
		return
	}
	if tc := h.clientOf(target); tc != nil {
		tc.sendJSON(map[string]any{"type": "kicked", "room": room.ID})
	}
	h.broadcastMembers(room.ID)
}

func (h *Hub) rename(c *client, in wsIn) {
	room, err := h.store.RoomByID(in.Room)
	if err != nil || room == nil {
		c.sendError("房间不存在")
		return
	}
	if room.Creator != c.deviceID {
		c.sendError("没有管理权限")
		return
	}
	name := roomName(in.Name)
	if name == "" {
		c.sendError("房间名不能为空")
		return
	}
	if err := h.store.RenameRoom(room.ID, name); err != nil {
		c.sendError("修改失败")
		return
	}
	room.Name = name
	h.broadcastRoom(room.ID, map[string]any{"type": "room", "room": h.roomView(*room)})
}

func (h *Hub) broadcastMembers(roomID string) {
	room, err := h.store.RoomByID(roomID)
	if err != nil || room == nil {
		return
	}
	h.broadcastRoom(roomID, map[string]any{
		"type":    "members",
		"room":    roomID,
		"members": h.memberViews(*room),
	})
}

func (h *Hub) broadcastRoom(roomID string, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	var targets []*client
	if roomID == publicRoomID {
		h.mu.RLock()
		for _, c := range h.clients {
			targets = append(targets, c)
		}
		h.mu.RUnlock()
	} else {
		ids, err := h.store.MemberIDs(roomID)
		if err != nil {
			return
		}
		for _, id := range ids {
			if c := h.clientOf(id); c != nil {
				targets = append(targets, c)
			}
		}
	}
	for _, c := range targets {
		c.enqueue(data)
	}
}

func (h *Hub) roomViews(deviceID string) []roomView {
	rooms, err := h.store.RoomsFor(deviceID)
	if err != nil {
		return []roomView{}
	}
	out := make([]roomView, 0, len(rooms))
	for _, r := range rooms {
		out = append(out, h.roomView(r))
	}
	return out
}

func (h *Hub) roomView(r Room) roomView {
	count := h.store.MemberCount(r.ID)
	if r.ID == publicRoomID {
		count = h.onlineCount()
	}
	return roomView{
		ID:          r.ID,
		Code:        r.Code,
		Name:        r.Name,
		Creator:     r.Creator,
		CreatedAt:   r.CreatedAt,
		MemberCount: count,
	}
}

func (h *Hub) memberViews(room Room) []memberView {
	if room.ID == publicRoomID {
		h.mu.RLock()
		out := make([]memberView, 0, len(h.clients))
		for id, c := range h.clients {
			out = append(out, memberView{
				DeviceID: id,
				Name:     c.name,
				Platform: c.platform,
				Online:   true,
			})
		}
		h.mu.RUnlock()
		sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
		return out
	}
	members, err := h.store.Members(room.ID)
	if err != nil {
		return []memberView{}
	}
	for i := range members {
		members[i].Online = h.online(members[i].DeviceID)
		members[i].Creator = members[i].DeviceID == room.Creator
	}
	return members
}

func (h *Hub) joinAllowed(deviceID string) bool {
	h.limMu.Lock()
	defer h.limMu.Unlock()
	cutoff := time.Now().Add(-time.Minute)
	list := h.joins[deviceID]
	kept := list[:0]
	for _, at := range list {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	if len(kept) >= 10 {
		h.joins[deviceID] = kept
		return false
	}
	h.joins[deviceID] = append(kept, time.Now())
	return true
}

func roomName(value string) string {
	return truncate(strings.TrimSpace(value), maxRoomName)
}

func messageID(value string) string {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 64 {
		return randomID()
	}
	for _, r := range value {
		ok := r == '-' || r == '_' ||
			(r >= '0' && r <= '9') ||
			(r >= 'a' && r <= 'z') ||
			(r >= 'A' && r <= 'Z')
		if !ok {
			return randomID()
		}
	}
	return value
}

func truncate(value string, max int) string {
	if utf8.RuneCountInString(value) <= max {
		return value
	}
	runes := []rune(value)
	return string(runes[:max])
}
