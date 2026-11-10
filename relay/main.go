package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	mimepkg "mime"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	_ "time/tzdata"
)

type app struct {
	key       string
	dataDir   string
	filesDir  string
	maxFile   int64
	maxFileMB int64
	hub       *Hub
	store     *Store
}

func main() {
	// 所有时间统一按中国时间记录和展示。
	if loc, err := time.LoadLocation("Asia/Shanghai"); err == nil {
		time.Local = loc
	}

	addr := flag.String("addr", "127.0.0.1:43500", "监听地址，通常放在反向代理后面")
	dataDir := flag.String("data", "data", "数据目录（SQLite 与文件都放在这里）")
	publicURL := flag.String("public-url", "", "对外访问地址，例如 https://chat.example.com")
	keyFlag := flag.String("key", "", "访问密钥；不填则自动生成并保存在数据目录")
	maxFileMB := flag.Int64("max-file-mb", 20, "单个文件大小上限（MB）")
	flag.Parse()

	if *maxFileMB <= 0 {
		*maxFileMB = 20
	}

	absData, err := filepath.Abs(*dataDir)
	if err != nil {
		log.Fatalf("数据目录无效: %v", err)
	}
	filesDir := filepath.Join(absData, "files")
	if err := os.MkdirAll(filesDir, 0o755); err != nil {
		log.Fatalf("创建数据目录失败: %v", err)
	}

	key, err := loadKey(absData, *keyFlag)
	if err != nil {
		log.Fatalf("密钥无效: %v", err)
	}

	store, err := OpenStore(filepath.Join(absData, "relay.db"))
	if err != nil {
		log.Fatalf("打开数据库失败: %v", err)
	}
	defer store.Close()

	a := &app{
		key:       key,
		dataDir:   absData,
		filesDir:  filesDir,
		maxFile:   *maxFileMB * 1024 * 1024,
		maxFileMB: *maxFileMB,
		hub:       NewHub(store),
		store:     store,
	}

	base := strings.TrimRight(strings.TrimSpace(*publicURL), "/")
	if base == "" {
		base = "http://" + displayAddr(*addr)
	}
	accessURL := base + "/xlan/" + key

	log.Printf("X互传 公网聊天中继已启动")
	log.Printf("监听地址：%s", *addr)
	log.Printf("访问地址：%s", accessURL)
	log.Printf("数据目录：%s", absData)
	log.Printf("文件上限：%d MB", *maxFileMB)
	if strings.TrimSpace(*publicURL) == "" {
		log.Printf("提示：用 -public-url 指定对外域名，日志里就会打印完整可填写的地址")
	}

	server := &http.Server{
		Addr:              *addr,
		Handler:           http.HandlerFunc(a.serveHTTP),
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
		<-sig
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()

	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("服务启动失败: %v", err)
	}
}

func displayAddr(addr string) string {
	if strings.HasPrefix(addr, ":") {
		return "127.0.0.1" + addr
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return net.JoinHostPort(host, port)
}

func validKey(key string) bool {
	if len(key) < 4 || len(key) > 64 {
		return false
	}
	for _, r := range key {
		ok := r == '-' || r == '_' ||
			(r >= '0' && r <= '9') ||
			(r >= 'a' && r <= 'z') ||
			(r >= 'A' && r <= 'Z')
		if !ok {
			return false
		}
	}
	return true
}

func loadKey(dir, override string) (string, error) {
	if override != "" {
		if !validKey(override) {
			return "", errors.New("密钥只能包含字母、数字、- 和 _，长度 4 到 64")
		}
		return override, nil
	}
	file := filepath.Join(dir, "key")
	if data, err := os.ReadFile(file); err == nil {
		key := strings.TrimSpace(string(data))
		if validKey(key) {
			return key, nil
		}
	}
	key := randomKey(20)
	if err := os.WriteFile(file, []byte(key+"\n"), 0o600); err != nil {
		return "", err
	}
	return key, nil
}

func randomKey(n int) string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	for i := range buf {
		buf[i] = alphabet[int(buf[i])%len(alphabet)]
	}
	return string(buf)
}

func (a *app) serveHTTP(w http.ResponseWriter, r *http.Request) {
	const root = "/xlan/"
	if !strings.HasPrefix(r.URL.Path, root) {
		http.NotFound(w, r)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, root)
	seg, tail, _ := strings.Cut(rest, "/")
	if subtle.ConstantTimeCompare([]byte(seg), []byte(a.key)) != 1 {
		http.NotFound(w, r)
		return
	}
	tail = strings.TrimSuffix(tail, "/")
	switch {
	case tail == "":
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":          true,
			"name":        "X互传 公网聊天中继",
			"maxFileSize": a.maxFile,
		})
	case tail == "ws":
		a.hub.HandleWS(w, r)
	case tail == "files":
		a.upload(w, r)
	case strings.HasPrefix(tail, "files/"):
		a.download(w, r, strings.TrimPrefix(tail, "files/"))
	default:
		http.NotFound(w, r)
	}
}

func (a *app) upload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" {
		name = "file"
	}
	name = truncate(name, 200)
	mime := strings.TrimSpace(r.URL.Query().Get("mime"))
	if mime == "" {
		mime = r.Header.Get("Content-Type")
	}
	if mime == "" || mime == "application/octet-stream" {
		mime = mimepkg.TypeByExtension(filepath.Ext(name))
	}
	if mime == "" {
		mime = "application/octet-stream"
	}

	body := http.MaxBytesReader(w, r.Body, a.maxFile)
	defer body.Close()

	id := randomID()
	target := filepath.Join(a.filesDir, id)
	file, err := os.Create(target)
	if err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	size, copyErr := io.Copy(file, body)
	closeErr := file.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(target)
		var tooLarge *http.MaxBytesError
		if errors.As(copyErr, &tooLarge) {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{
				"error": fmt.Sprintf("文件超过 %d MB", a.maxFileMB),
			})
			return
		}
		http.Error(w, "upload failed", http.StatusBadRequest)
		return
	}
	if size <= 0 {
		_ = os.Remove(target)
		http.Error(w, "empty file", http.StatusBadRequest)
		return
	}
	if err := a.store.SaveFile(FileInfo{ID: id, Name: name, Size: size, Mime: mime}); err != nil {
		_ = os.Remove(target)
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id":   id,
		"name": name,
		"size": size,
		"mime": mime,
	})
}

func (a *app) download(w http.ResponseWriter, r *http.Request, id string) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !validFileID(id) {
		http.NotFound(w, r)
		return
	}
	info, err := a.store.File(id)
	if err != nil || info == nil {
		http.NotFound(w, r)
		return
	}
	file, err := os.Open(filepath.Join(a.filesDir, id))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()

	w.Header().Set("Content-Type", info.Mime)
	w.Header().Set("X-File-Name", strconv.QuoteToASCII(info.Name))
	if disposition := mimepkg.FormatMediaType("attachment", map[string]string{"filename": info.Name}); disposition != "" {
		w.Header().Set("Content-Disposition", disposition)
	}
	http.ServeContent(w, r, info.Name, time.Time{}, file)
}

func validFileID(id string) bool {
	if len(id) != 16 {
		return false
	}
	for _, r := range id {
		ok := (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f')
		if !ok {
			return false
		}
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
