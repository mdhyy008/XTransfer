; 安装器自定义：欢迎页显示软件说明，默认装到非 C 盘。
!include LogicLib.nsh

!ifndef BUILD_UNINSTALLER
  Var xltNonCDir
!endif

!macro customWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "欢迎安装 X互传"
  !define MUI_WELCOMEPAGE_TEXT "X互传是一款在局域网内互传文件和消息的工具：同一网络下的手机、电脑可以直接互相发送文件，也支持公网聊天中继、蓝牙和热点分享。$\r$\n$\r$\n安装时可以自行选择安装目录；软件的数据和缓存都保存在安装目录里，不会写进 C 盘。$\r$\n$\r$\n开发者：明灯花月夜　网址：mdhyy.cn"
  !insertmacro MUI_PAGE_WELCOME
!macroend

; 取第一个存在的非 C 盘固定磁盘（D~G），没有就留空。
!macro xltPickNonCDir
  StrCpy $xltNonCDir ""
  StrCpy $R0 68
  ${Do}
    ${If} $R0 > 71
      ${ExitDo}
    ${EndIf}
    IntFmt $R1 "%c" $R0
    StrCpy $R2 "$R1:"
    StrCpy $R2 "$R2\"
    System::Call 'kernel32::GetDriveTypeW(w "$R2") i .R3'
    ${If} $R3 == 3
      StrCpy $xltNonCDir "$R2${APP_FILENAME}"
      ${ExitDo}
    ${EndIf}
    IntOp $R0 $R0 + 1
  ${Loop}
!macroend

!macro customInit
  !insertmacro xltPickNonCDir
  ${If} $xltNonCDir != ""
    StrCpy $INSTDIR $xltNonCDir
    ; 安装模式页会按注册表记录重设 $INSTDIR，先把默认目录记下，之后才不会被拉回 C 盘。
    ReadRegStr $R0 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
    ${If} $R0 == ""
      WriteRegStr HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation "$INSTDIR"
    ${EndIf}
  ${EndIf}
!macroend
