package com.example.miband5.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.miband5.metrics.Badge

@Composable
fun VitalsScreen(viewModel: MetricsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SectionCard(title = "\u25CE Today's score") {
            val score = state.score
            if (score == null) {
                Text("Not enough data yet for today", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${score.score}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(score.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                score.parts.forEach { part ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(part.name, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        LinearProgressIndicator(
                            progress = { part.pct },
                            modifier = Modifier.weight(1f).height(6.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(" ${(part.pct * 100).toInt()}%", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        } }

        item { SectionCard(title = "\u26A1 Strain") {
            val strain = state.strain
            Text(strain?.let { "%.1f".format(it) } ?: "\u2013", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(state.strainLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } }

        item { SectionCard(title = "\uD83C\uDFC5 Achievements") {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(160.dp)
            ) {
                items(state.badges) { badge -> BadgeTile(badge) }
            }
        } }

        item { SectionCard(title = "\u2610 Patterns") {
            if (state.correlationLines.isEmpty()) {
                Text(
                    "Log journal tags on a few more days to see patterns in your own data here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                state.correlationLines.forEach { line ->
                    Text(line, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        } }
    }
}

@Composable
private fun BadgeTile(badge: Badge) {
    Column(
        modifier = Modifier.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(badge.icon, fontSize = 22.sp, color = if (badge.earned) Color.Unspecified else Color.Gray.copy(alpha = 0.4f))
        Text(
            badge.name,
            fontSize = 10.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (badge.earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope
