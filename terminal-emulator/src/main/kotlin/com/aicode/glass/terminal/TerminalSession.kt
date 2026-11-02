package com.aicode.glass.terminal

import java.io.File

/** Workspace shell contract. No Android, root, PTY, or Linux container is implied. */
class TerminalSession(val root: File) {
    val home: File get() = root.canonicalFile
}
