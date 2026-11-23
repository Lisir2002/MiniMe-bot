package com.mini.bot.feature.agreement.presentation

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.mini.bot.R
import com.mini.bot.feature.manual.presentation.ManualActivity
import com.mini.bot.core.theme.semanticColors

@Composable
fun AgreementDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.semanticColors.info,
            textDecoration = TextDecoration.Underline
        )
    )
    val notice = buildAnnotatedString {
        append(stringResource(R.string.agreement_notice_prefix))
        withLink(
            LinkAnnotation.Clickable("terms", linkStyles) {
                runCatching { openManual(context, ManualActivity.PAGE_TERMS) }
            }
        ) {
            append(stringResource(R.string.agreement_terms_link))
        }
        append(stringResource(R.string.agreement_notice_conjunction))
        withLink(
            LinkAnnotation.Clickable("privacy", linkStyles) {
                runCatching { openManual(context, ManualActivity.PAGE_PRIVACY) }
            }
        ) {
            append(stringResource(R.string.agreement_privacy_link))
        }
        append(stringResource(R.string.agreement_notice_suffix))
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.agreement_title)) },
        text = {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.agreement_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.agreement_decline))
            }
        }
    )
}


private fun openManual(context: android.content.Context, page: String) {
    context.startActivity(
        Intent(context, ManualActivity::class.java).putExtra(ManualActivity.EXTRA_PAGE, page)
    )
}
