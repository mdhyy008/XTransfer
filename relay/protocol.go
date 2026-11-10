package main

const (
	// 所有连接都在这个房间里，不需要加入。
	publicRoomID = "public"
	publicName   = "公网广播"

	kindText  = "text"
	kindImage = "image"
	kindFile  = "file"

	maxTextRunes = 4000
	maxRoomName  = 32
)

// 客户端发来的每一帧。
type wsIn struct {
	Type      string `json:"type"`
	ID        string `json:"id,omitempty"`
	Room      string `json:"room,omitempty"`
	Code      string `json:"code,omitempty"`
	Name      string `json:"name,omitempty"`
	Kind      string `json:"kind,omitempty"`
	Body      string `json:"body,omitempty"`
	FileID    string `json:"fileId,omitempty"`
	FileName  string `json:"fileName,omitempty"`
	FileSize  int64  `json:"fileSize,omitempty"`
	FileMime  string `json:"fileMime,omitempty"`
	BeforeSeq int64  `json:"beforeSeq,omitempty"`
	Limit     int    `json:"limit,omitempty"`
	Target    string `json:"deviceId,omitempty"`
}

type roomView struct {
	ID          string `json:"id"`
	Code        string `json:"code"`
	Name        string `json:"name"`
	Creator     string `json:"creator"`
	CreatedAt   int64  `json:"createdAt"`
	MemberCount int    `json:"memberCount"`
}

type memberView struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Platform string `json:"platform,omitempty"`
	Online   bool   `json:"online"`
	Creator  bool   `json:"creator"`
	JoinedAt int64  `json:"joinedAt"`
}

type messageView struct {
	ID        string `json:"id"`
	Room      string `json:"room"`
	From      string `json:"from"`
	FromName  string `json:"fromName"`
	Kind      string `json:"kind"`
	Body      string `json:"body,omitempty"`
	FileID    string `json:"fileId,omitempty"`
	FileName  string `json:"fileName,omitempty"`
	FileSize  int64  `json:"fileSize,omitempty"`
	FileMime  string `json:"fileMime,omitempty"`
	Recalled  bool   `json:"recalled"`
	Seq       int64  `json:"seq"`
	CreatedAt int64  `json:"createdAt"`
}
