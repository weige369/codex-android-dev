package com.codex.android.feature.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.core.designsystem.*

/**
 * Codex Android v2.0 — DevToolsScreen (GitHub 增强版).
 *
 * Layout (5 sections):
 *   1. Processes Manager
 *   2. Ports Manager
 *   3. MCP Center
 *   4. GitHub Panel (User Card + Notifications + Actions)
 *   5. Git Status
 */

@Composable
fun DevToolsScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CxBackground),
        contentPadding = PaddingValues(CxSpaceLg),
        verticalArrangement = Arrangement.spacedBy(CxSpaceMd)
    ) {
        // ── 1. Process Manager ──
        item { SectionLabel("Processes") }
        item { ProcessCard("node", "1024", "12%", "300 MB") }
        item { ProcessCard("adb", "2048", "1%", "12 MB") }
        item { ProcessCard("codex", "3072", "5%", "64 MB") }

        // ── 2. Ports ──
        item { SectionLabel("Ports") }
        item { PortCard("3000", "React Dev Server") }
        item { PortCard("8080", "Spring Boot") }
        item { PortCard("9877", "Codex exec-server") }

        // ── 3. MCP Center ──
        item { SectionLabel("MCP Center") }
        item { McpServiceCard("Filesystem", "Connected", CxOnline) }
        item { McpServiceCard("GitHub", "Connected", CxOnline) }
        item { McpServiceCard("Browser", "Connected", CxOnline) }
        item { McpServiceCard("ADB", "Connected", CxOnline) }

        // ── 4. GitHub Panel ──
        item { SectionLabel("GitHub") }
        item { GitHubUserCard() }
        item { NotificationsSection() }
        item { ActionsSection() }

        // ── 5. Git Status ──
        item { SectionLabel("Git Status") }
        item { GitStatusCard("main", "3M", "1↑", "+3") }

        item { Spacer(Modifier.height(CxSpaceXl)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 1. Processes + Ports + MCP (v2.0 原有组件)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ProcessCard(name: String, pid: String, cpu: String, mem: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(pid, fontFamily = FontFamily.Monospace, color = CxTextTertiary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
            Text(name, fontWeight = FontWeight.Medium, color = CxTextPrimary, modifier = Modifier.weight(1f))
            Text(cpu, fontFamily = FontFamily.Monospace, color = CxTextSecondary, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            Text(mem, fontFamily = FontFamily.Monospace, color = CxTextSecondary, fontSize = 12.sp, modifier = Modifier.width(60.dp))
        }
    }
}

@Composable
private fun PortCard(port: String, service: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lan, null, tint = CxPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(CxSpaceSm))
            Text(":$port", fontFamily = FontFamily.Monospace, color = CxBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(CxSpaceMd))
            Text(service, color = CxTextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun McpServiceCard(name: String, status: String, color: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(CxSpaceSm))
            Text(name, fontWeight = FontWeight.Medium, color = CxTextPrimary, modifier = Modifier.weight(1f))
            Text(status, fontSize = 12.sp, color = color)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 4. GitHub Panel 组件
// ═══════════════════════════════════════════════════════════════════

/**
 * GitHub 用户卡片 — 显示已认证用户头像、用户名、仓库数、粉丝数。
 */
@Composable
private fun GitHubUserCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 圆形头像 placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CxPrimary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = CxPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(CxSpaceMd))
            Column(modifier = Modifier.weight(1f)) {
                Text("GitHub User", fontWeight = FontWeight.SemiBold, color = CxTextPrimary, fontSize = 15.sp)
                Text("登录后可查看仓库和通知", fontSize = 12.sp, color = CxTextTertiary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("0 repos", fontSize = 12.sp, color = CxTextSecondary)
                Text("0 followers", fontSize = 12.sp, color = CxTextSecondary)
            }
        }
    }
}

/**
 * 未读通知列表区域。
 */
@Composable
private fun NotificationsSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notifications", fontWeight = FontWeight.Medium, color = CxTextPrimary, fontSize = 14.sp)
                Text("0 unread", fontSize = 11.sp, color = CxTextTertiary)
            }
            Spacer(Modifier.height(CxSpaceSm))
            // Placeholder: 未连接时显示提示
            Text(
                "登录 GitHub 后查看通知",
                fontSize = 12.sp,
                color = CxTextTertiary,
                modifier = Modifier.padding(vertical = CxSpaceSm)
            )
        }
    }
}

/**
 * GitHub Actions 工作流运行状态区域。
 */
@Composable
private fun ActionsSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Text("Actions", fontWeight = FontWeight.Medium, color = CxTextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(CxSpaceSm))
            Text(
                "登录 GitHub 后查看工作流状态",
                fontSize = 12.sp,
                color = CxTextTertiary,
                modifier = Modifier.padding(vertical = CxSpaceSm)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 5. Git Status
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun GitStatusCard(branch: String, modified: String, staged: String, ahead: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Commit, null, tint = CxOnline, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(CxSpaceSm))
                Text(branch, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = CxTextPrimary, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CxSpaceMd)) {
                GitStatChip(modified, CxWarning)
                GitStatChip(staged, CxOnline)
                GitStatChip(ahead, CxPrimary)
            }
        }
    }
}

@Composable
private fun GitStatChip(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = CxSpaceSm, vertical = 2.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// Shared
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = CxTextTertiary,
        modifier = Modifier.padding(top = CxSpaceSm)
    )
}