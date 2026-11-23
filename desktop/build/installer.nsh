; 安装器自定义：欢迎页显示软件说明，默认装到非 C 盘。
!include LogicLib.nsh

!ifndef BUILD_UNINSTALLER
  Var xltNonCDir
  Var xltKeepDir
  Var xltDataPath
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

; 覆盖安装时，旧版卸载器会把整个安装目录清空；先把 data 挪到同一块盘的旁边，装完再挪回。
; 目录被正在运行的程序锁住时先结束进程再试一次，两次都失败就放弃（不影响正常安装）。
!macro xltParkData
  StrCpy $xltKeepDir ""
  StrCpy $xltDataPath ""
  ReadRegStr $R0 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
  ${If} $R0 == ""
    ReadRegStr $R0 HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation
  ${EndIf}
  ${If} $R0 != ""
  ${AndIf} ${FileExists} "$R0\data"
    StrCpy $xltDataPath "$R0\data"
    StrCpy $xltKeepDir "$R0\..\${APP_FILENAME}-data-keep"
    ; 上次安装中断留下的备份先放回原位，免得越积越多。
    ${If} ${FileExists} "$xltKeepDir"
    ${AndIfNot} ${FileExists} "$xltDataPath"
      Rename "$xltKeepDir" "$xltDataPath"
    ${EndIf}
    ${If} ${FileExists} "$xltKeepDir"
      StrCpy $xltKeepDir ""
      StrCpy $xltDataPath ""
    ${Else}
      ClearErrors
      Rename "$xltDataPath" "$xltKeepDir"
      ${If} ${Errors}
        nsExec::Exec 'taskkill /F /IM "${APP_EXECUTABLE_FILENAME}"'
        Pop $R1
        Sleep 800
        ClearErrors
        Rename "$xltDataPath" "$xltKeepDir"
        ${If} ${Errors}
          StrCpy $xltKeepDir ""
          StrCpy $xltDataPath ""
        ${EndIf}
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend

!macro xltRestoreData _TARGET
  ${If} $xltKeepDir != ""
    ${If} ${FileExists} "$xltKeepDir"
      ${IfNot} ${FileExists} "${_TARGET}"
        Rename "$xltKeepDir" "${_TARGET}"
      ${EndIf}
    ${EndIf}
    StrCpy $xltKeepDir ""
    StrCpy $xltDataPath ""
  ${EndIf}
!macroend

!macro customInstall
  !insertmacro xltRestoreData "$INSTDIR\data"
!macroend

; 用户在向导中途取消时，把挪走的 data 放回原处。
!ifndef BUILD_UNINSTALLER
Function .onGUIEnd
  !insertmacro xltRestoreData "$xltDataPath"
FunctionEnd
!endif

!macro customInit
  !insertmacro xltParkData
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

; 目录页：用户重新选了目录后，自动补一层「软件名」子目录并回显到路径框里，
; 安装文件落在子目录中而不是散在用户选的目录里。
; NSIS 在目录变化（含浏览选完目录）时回调 .onVerifyInstDir，此刻页面还在，可以改路径框。
!ifndef BUILD_UNINSTALLER
  Function .onVerifyInstDir
    ; 只处理目录选择页：该页有「所需空间」提示控件。
    FindWindow $R9 "#32770" "" $HWNDPARENT
    ${If} $R9 == 0
      Return
    ${EndIf}
    GetDlgItem $R8 $R9 1023
    ${If} $R8 == 0
      Return
    ${EndIf}
    GetDlgItem $R7 $R9 1019
    ${If} $R7 == 0
      Return
    ${EndIf}
    ; 用户正在路径框里打字时不插手，只在焦点离开（比如浏览选完目录回来）后补。
    System::Call 'user32::GetFocus() p .R0'
    ${If} $R0 == $R7
      Return
    ${EndIf}
    System::Call 'user32::GetWindowTextW(p r7, w .R1, i ${NSIS_MAX_STRLEN})'
    ${If} $R1 == ""
      Return
    ${EndIf}
    ; 去掉结尾反斜杠再判断，避免拼出双斜杠。
    StrCpy $R3 $R1 1 -1
    ${If} $R3 == "\"
      StrCpy $R1 $R1 -1
    ${EndIf}
    ${If} $R1 == ""
      Return
    ${EndIf}
    ; 路径已经以软件名结尾就不用再补。
    StrLen $R4 "\${APP_FILENAME}"
    StrLen $R5 $R1
    ${If} $R5 >= $R4
      IntOp $R6 $R5 - $R4
      StrCpy $R3 $R1 "" $R6
      ${If} $R3 == "\${APP_FILENAME}"
        StrCpy $INSTDIR $R1
        Return
      ${EndIf}
    ${EndIf}
    StrCpy $R2 "$R1\${APP_FILENAME}"
    StrCpy $INSTDIR $R2
    System::Call 'user32::SetWindowTextW(p r7, w r2)'
  FunctionEnd
!endif
