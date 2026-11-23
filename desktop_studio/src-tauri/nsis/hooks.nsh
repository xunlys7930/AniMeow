; AniMeow 桌面端 NSIS 安装 / 卸载增强脚本
; 设计原则：界面清爽美观、选项透明克制、默认不启用开机自启动、卸载不残留任何启动项

; ---------- 1. 安装 / 卸载向导文案与排版 ----------
!define MUI_WELCOMEPAGE_TITLE "欢迎安装 追番喵 (AniMeow)"
!define MUI_WELCOMEPAGE_TITLE_3LINES
!define MUI_WELCOMEPAGE_TEXT "追番喵桌面工坊用于记录追番与整理藏品。$\r$\n$\r$\n本地资料库独立保存，可在新版中导入旧版资料副本。$\r$\n开机自启动默认关闭，可在下方选择是否开启。"

!define MUI_FINISHPAGE_TITLE "追番喵已准备就绪"
!define MUI_FINISHPAGE_TITLE_3LINES
!define MUI_FINISHPAGE_TEXT "追番喵已顺利安装到您的电脑。$\r$\n$\r$\n您可以自由决定是否立即启动或创建桌面快捷方式；所有系统偏好后续随时可在软件「设置」中调整。"
!define MUI_FINISHPAGE_RUN_TEXT "立即启动 追番喵"

!define MUI_UNCONFIRMPAGE_TEXT_TOP "即将从您的电脑移除追番喵程序本体。$\r$\n$\r$\n您的本地收藏库、观看进度和个人设置默认会安全保留；若曾开启开机自启动，也会一并清理干净。如需连同个人数据一并彻底清除，请手动勾选下方选项。"

; ---------- 2. 欢迎页克制选项：开机自动启动（默认不勾选，不做流氓软件） ----------
Var OptAutostartCheckbox
Var OptAutostartChecked

Function AniMeowWelcomeShow
  StrCpy $OptAutostartChecked 0
  ; 自动更新 / 静默安装模式跳过交互勾选框
  ${GetOptions} $CMDLINE "/P" $0
  IfErrors +2 0
    Return
  ${GetOptions} $CMDLINE "/UPDATE" $0
  IfErrors +2 0
    Return
  ; 在欢迎页正文下方展示透明的开机自启动可选项（默认未勾选）
  ${NSD_CreateCheckbox} 120u 144u 195u 14u "开机自动启动追番喵（默认不开启）"
  Pop $OptAutostartCheckbox
  SetCtlColors $OptAutostartCheckbox "" "FFFFFF"
  ${NSD_Uncheck} $OptAutostartCheckbox
FunctionEnd

Function AniMeowWelcomeLeave
  ${If} $OptAutostartCheckbox <> 0
    ${NSD_GetState} $OptAutostartCheckbox $OptAutostartChecked
  ${EndIf}
FunctionEnd

!define MUI_PAGE_CUSTOMFUNCTION_SHOW AniMeowWelcomeShow
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE AniMeowWelcomeLeave

!macro AniMeowRetargetShortcut shortcut expectedTarget newTarget
  !insertmacro IsShortcutTarget "${shortcut}" "${expectedTarget}"
  Pop $0
  ${If} $0 = 1
    !insertmacro SetShortcutTarget "${shortcut}" "${newTarget}"
  ${EndIf}
!macroend

!macro AniMeowFindLegacyStudio
  StrCpy $R6 ""
  ReadRegStr $R7 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AniMeow" "MainBinaryName"
  ${If} $R7 == "${MAINBINARYNAME}.exe"
    ReadRegStr $R6 HKCU "${MANUKEY}\AniMeow" ""
  ${EndIf}
!macroend

!macro AniMeowRetargetLegacyShortcuts expectedTarget newTarget
  !insertmacro AniMeowRetargetShortcut "$DESKTOP\AniMeow.lnk" "${expectedTarget}" "${newTarget}"
  !insertmacro AniMeowRetargetShortcut "$SMPROGRAMS\${STARTMENUFOLDER}\AniMeow.lnk" "${expectedTarget}" "${newTarget}"
  !insertmacro AniMeowRetargetShortcut "$SMPROGRAMS\AniMeow.lnk" "${expectedTarget}" "${newTarget}"
!macroend

; ---------- 3. 安装后 / 卸载后钩子 ----------
!macro NSIS_HOOK_POSTINSTALL
  ; 从旧安装名迁移时，静默更新可能没有 Studio 专用的开始菜单入口。
  ${If} $UpdateMode = 1
  ${AndIf} $NoShortcutMode <> 1
  ${AndIfNot} ${FileExists} "$SMPROGRAMS\$AppStartMenuFolder\${PRODUCTNAME}.lnk"
  ${AndIfNot} ${FileExists} "$SMPROGRAMS\${PRODUCTNAME}.lnk"
    CreateDirectory "$SMPROGRAMS\$AppStartMenuFolder"
    CreateShortcut "$SMPROGRAMS\$AppStartMenuFolder\${PRODUCTNAME}.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
    !insertmacro SetLnkAppUserModelId "$SMPROGRAMS\$AppStartMenuFolder\${PRODUCTNAME}.lnk"
  ${EndIf}

  ${If} $NoShortcutMode <> 1
    !insertmacro AniMeowFindLegacyStudio
    ${If} $R6 != ""
    ${AndIf} $R6 != $INSTDIR
    ${AndIf} ${FileExists} "$R6\${MAINBINARYNAME}.exe"
      !insertmacro AniMeowRetargetLegacyShortcuts "$R6\${MAINBINARYNAME}.exe" "$INSTDIR\${MAINBINARYNAME}.exe"
    ${EndIf}
  ${EndIf}

  ; 仅在常规安装且用户主动勾选「开机自动启动」时写入当前用户启动项（不申请管理员权限）
  ; 自动更新（$UpdateMode = 1）时保持用户原有设置不动
  ${If} $UpdateMode <> 1
  ${AndIf} $OptAutostartChecked = ${BST_CHECKED}
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${PRODUCTNAME}" '"$INSTDIR\${MAINBINARYNAME}.exe"'
  ${EndIf}
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $UpdateMode <> 1
    !insertmacro AniMeowFindLegacyStudio
    ${If} $R6 != ""
    ${AndIf} $R6 != $INSTDIR
    ${AndIf} ${FileExists} "$R6\${MAINBINARYNAME}.exe"
      !insertmacro AniMeowRetargetLegacyShortcuts "$INSTDIR\${MAINBINARYNAME}.exe" "$R6\${MAINBINARYNAME}.exe"
    ${EndIf}
  ${EndIf}
!macroend
