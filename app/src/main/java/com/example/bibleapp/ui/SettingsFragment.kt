package com.example.bibleapp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.Gravity
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.example.bibleapp.data.AiStore
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.databinding.FragmentSettingsBinding
import com.example.bibleapp.util.BackupManager
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // 백업 zip 선택 → 가져오기
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runImport(uri)
        }

    // 교환 .json 선택 → 가져오기 (아이폰/웹 호환)
    private val importJsonLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runImportJson(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnUsage.setOnClickListener { showUsageDialog() }
        binding.btnLog.setOnClickListener { showLogDialog() }
        binding.btnBulletinUrl.setOnClickListener { showBulletinDialog() }

        // 표시(테마)
        binding.btnTheme.setOnClickListener { showThemeDialog() }
        // 구절 제스처
        binding.btnGestureSingle.setOnClickListener { showGestureDialog(0, "한 번 눌렀을 때") }
        binding.btnGestureDouble.setOnClickListener { showGestureDialog(1, "두 번 연속 눌렀을 때") }
        binding.btnGestureTriple.setOnClickListener { showGestureDialog(2, "세 번 연속 눌렀을 때") }
        binding.btnGestureLong.setOnClickListener   { showGestureDialog(3, "꾹 눌렀을 때") }
        // 공유 문구
        binding.btnSignature.setOnClickListener { showSignatureDialog() }

        // AI 설정
        binding.swAiEnabled.isChecked = AiStore.isAiEnabled(requireContext())
        binding.swAiEnabled.setOnCheckedChangeListener { _, on -> AiStore.setAiEnabled(requireContext(), on) }
        binding.btnAiModel.setOnClickListener { showAiModelDialog("general") }
        binding.btnAiBulletinModel.setOnClickListener { showAiModelDialog("bulletin") }
        binding.btnAiKey.setOnClickListener { showAiKeysDialog() }
        binding.btnAiVersePrompt.setOnClickListener { showPromptDialog(true) }
        binding.btnAiBulletinPrompt.setOnClickListener { showPromptDialog(false) }

        // 데이터 백업/가져오기
        binding.btnBackup.setOnClickListener { runExport(csv = false) }
        binding.btnCsv.setOnClickListener { runExport(csv = true) }
        binding.btnImport.setOnClickListener { confirmImport() }
        binding.btnExportJson.setOnClickListener { runExportJson() }
        binding.btnImportJson.setOnClickListener { confirmImportJson() }

        refreshValues()
    }

    // ── 내보내기 (워커 스레드) ──
    private fun runExport(csv: Boolean) {
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx)
            .setMessage(if (csv) "CSV 만드는 중…" else "백업 만드는 중…")
            .setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching {
                if (csv) BackupManager.exportCsvZip(ctx) else BackupManager.exportBackupZip(ctx)
            }
            activity?.runOnUiThread {
                progress.dismiss()
                result.onSuccess { shareFile(it) }
                    .onFailure { toast("내보내기 실패: ${it.message}") }
            }
        }.start()
    }

    private fun shareFile(file: File, mime: String = "application/zip") {
        val ctx = context ?: return
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "파일 공유/저장"))
        } catch (e: Exception) {
            toast("공유 실패: ${e.message}")
        }
    }

    // ── 교환 .json 내보내기 (아이폰/웹 호환) ──
    private fun runExportJson() {
        AlertDialog.Builder(requireContext())
            .setTitle("내보내기")
            .setMessage("내보내기 파일에 API 키 정보도 포함할까요?\n(프롬프트·AI 메모는 항상 포함됩니다)")
            .setPositiveButton("키 포함") { _, _ -> doExportJson(true) }
            .setNegativeButton("키 제외") { _, _ -> doExportJson(false) }
            .setNeutralButton("취소", null)
            .show()
    }

    private fun doExportJson(includeKeys: Boolean) {
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx)
            .setMessage("파일 만드는 중…").setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching { BackupManager.exportInterchangeJson(ctx, includeKeys) }
            activity?.runOnUiThread {
                progress.dismiss()
                result.onSuccess { shareFile(it, "application/json") }
                    .onFailure { toast("내보내기 실패: ${it.message}") }
            }
        }.start()
    }

    private fun confirmImportJson() {
        AlertDialog.Builder(requireContext())
            .setTitle("아이폰 호환 가져오기")
            .setMessage(".json을 선택하면 현재 북마크·메모·QT 기록이 파일 내용으로 교체됩니다. 계속할까요?")
            .setPositiveButton("파일 선택") { _, _ ->
                runCatching { importJsonLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
                    .onFailure { toast("파일 선택기를 열 수 없습니다") }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun runImportJson(uri: Uri) {
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx).setMessage("가져오는 중…").setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching { BackupManager.importInterchangeJson(ctx, uri) }
            activity?.runOnUiThread {
                progress.dismiss()
                result.onSuccess {
                    AlertDialog.Builder(ctx).setTitle("완료").setMessage(it)
                        .setPositiveButton("확인") { _, _ -> activity?.recreate() }.show()
                }.onFailure { toast("가져오기 실패: ${it.message}") }
            }
        }.start()
    }

    // ── 가져오기 ──
    private fun confirmImport() {
        AlertDialog.Builder(requireContext())
            .setTitle("데이터 가져오기")
            .setMessage("백업(.zip)을 선택하면 현재 메모·QT 기록이 백업 내용으로 교체됩니다. 계속할까요?")
            .setPositiveButton("파일 선택") { _, _ ->
                runCatching { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                    .onFailure { toast("파일 선택기를 열 수 없습니다") }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun runImport(uri: Uri) {
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx).setMessage("가져오는 중…").setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching { BackupManager.importBackupZip(ctx, uri) }
            activity?.runOnUiThread {
                progress.dismiss()
                result.onSuccess {
                    AlertDialog.Builder(ctx).setTitle("완료").setMessage(it)
                        .setPositiveButton("확인") { _, _ -> activity?.recreate() }.show()
                }.onFailure { toast("가져오기 실패: ${it.message}") }
            }
        }.start()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun refreshValues() {
        val ctx = requireContext()
        binding.tvThemeValue.text = THEME_LABELS[BookmarkStore.getThemeMode(ctx).coerceIn(0, 2)]
        binding.tvGestureSingleValue.text = VerseAction.from(BookmarkStore.getGestureAction(ctx, 0)).label
        binding.tvGestureDoubleValue.text = VerseAction.from(BookmarkStore.getGestureAction(ctx, 1)).label
        binding.tvGestureTripleValue.text = VerseAction.from(BookmarkStore.getGestureAction(ctx, 2)).label
        binding.tvGestureLongValue.text   = VerseAction.from(BookmarkStore.getGestureAction(ctx, 3)).label
        val sig = BookmarkStore.getShareSignature(ctx)
        binding.tvSignatureValue.text = if (sig.isBlank()) "기본 (한영Bible)" else sig
        val prov = AiStore.getProvider(ctx)
        binding.tvAiModelValue.text = "${AiStore.providerLabel(prov)} · ${AiStore.getModel(ctx)}"
        binding.tvAiKeyValue.text = AiStore.maskedKey(ctx, prov)
        val bprov = AiStore.getBulletinProvider(ctx)
        binding.tvAiBulletinModelValue.text = "${AiStore.providerLabel(bprov)} · ${AiStore.getBulletinModel(ctx)}"
    }

    // ── AI 설정 다이얼로그 ──
    // ── AI 설정: 세련된 커스텀 팝업 ──
    private fun makeSheet(layoutRes: Int): Pair<AlertDialog, View> {
        val v = layoutInflater.inflate(layoutRes, null)
        val d = AlertDialog.Builder(requireContext()).setView(v).create()
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return d to v
    }

    /** target: "general" | "bulletin"(이미지 인식=비전 모델) */
    private fun showAiModelDialog(target: String) {
        val ctx = requireContext()
        val isBul = target == "bulletin"
        val (dialog, v) = makeSheet(com.example.bibleapp.R.layout.dialog_ai_model)
        val rgProvider = v.findViewById<android.widget.RadioGroup>(com.example.bibleapp.R.id.rg_provider)
        val rbGemini = v.findViewById<android.widget.RadioButton>(com.example.bibleapp.R.id.rb_gemini)
        val rbGroq = v.findViewById<android.widget.RadioButton>(com.example.bibleapp.R.id.rb_groq)
        val spinner = v.findViewById<android.widget.Spinner>(com.example.bibleapp.R.id.spinner_model)

        var provider = if (isBul) AiStore.getBulletinProvider(ctx) else AiStore.getProvider(ctx)
        if (provider == "groq") rbGroq.isChecked = true else rbGemini.isChecked = true

        fun modelsOf(prov: String) = if (isBul) AiStore.visionModelsFor(prov) else AiStore.modelsFor(prov)
        fun fillSpinner(prov: String, select: String) {
            val models = modelsOf(prov)
            val ad = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_item, models)
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = ad
            val idx = models.indexOf(select).let { if (it >= 0) it else 0 }
            spinner.setSelection(idx)
        }
        val curModel = if (isBul) AiStore.getBulletinModel(ctx) else AiStore.getModel(ctx)
        fillSpinner(provider, curModel)
        rgProvider.setOnCheckedChangeListener { _, id ->
            provider = if (id == com.example.bibleapp.R.id.rb_groq) "groq" else "gemini"
            fillSpinner(provider, modelsOf(provider).first())
        }
        v.findViewById<View>(com.example.bibleapp.R.id.model_cancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(com.example.bibleapp.R.id.model_save).setOnClickListener {
            val model = spinner.selectedItem?.toString() ?: curModel
            if (isBul) { AiStore.setBulletinProvider(ctx, provider); AiStore.setBulletinModel(ctx, model) }
            else { AiStore.setProvider(ctx, provider); AiStore.setModel(ctx, model) }
            refreshValues(); dialog.dismiss()
        }
        dialog.show()
    }

    private fun showAiKeysDialog() {
        val ctx = requireContext()
        val (dialog, v) = makeSheet(com.example.bibleapp.R.layout.dialog_ai_keys)
        val geEdit = v.findViewById<EditText>(com.example.bibleapp.R.id.key_gemini_edit)
        val grEdit = v.findViewById<EditText>(com.example.bibleapp.R.id.key_groq_edit)
        val geStatus = v.findViewById<android.widget.TextView>(com.example.bibleapp.R.id.status_gemini)
        val grStatus = v.findViewById<android.widget.TextView>(com.example.bibleapp.R.id.status_groq)
        geEdit.setText(AiStore.getKey(ctx, "gemini"))
        grEdit.setText(AiStore.getKey(ctx, "groq"))

        fun testKey(provider: String, key: String, status: android.widget.TextView) {
            val k = key.trim()
            if (k.isBlank()) { status.text = "키를 입력하세요"; return }
            status.text = "확인 중…"
            val model = AiStore.modelsFor(provider).first()
            Thread {
                val r = runCatching { com.example.bibleapp.util.AiClient.testKey(provider, model, k) }
                activity?.runOnUiThread {
                    status.text = r.fold({ "정상 ✅" }, { "실패: ${it.message?.take(40)}" })
                }
            }.start()
        }
        v.findViewById<View>(com.example.bibleapp.R.id.btn_test_gemini).setOnClickListener {
            testKey("gemini", geEdit.text.toString(), geStatus)
        }
        v.findViewById<View>(com.example.bibleapp.R.id.btn_test_groq).setOnClickListener {
            testKey("groq", grEdit.text.toString(), grStatus)
        }
        v.findViewById<View>(com.example.bibleapp.R.id.keys_cancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(com.example.bibleapp.R.id.keys_save).setOnClickListener {
            AiStore.setKey(ctx, "gemini", geEdit.text.toString().trim())
            AiStore.setKey(ctx, "groq", grEdit.text.toString().trim())
            refreshValues(); toast("키 저장됨"); dialog.dismiss()
        }
        dialog.show()
    }

    private fun showPromptDialog(verse: Boolean) {
        val ctx = requireContext()
        val (dialog, v) = makeSheet(com.example.bibleapp.R.layout.dialog_ai_prompt)
        val title = v.findViewById<android.widget.TextView>(com.example.bibleapp.R.id.ai_prompt_title)
        val sub = v.findViewById<android.widget.TextView>(com.example.bibleapp.R.id.ai_prompt_sub)
        val edit = v.findViewById<EditText>(com.example.bibleapp.R.id.ai_prompt_edit)
        if (verse) {
            title.text = "구절 프롬프트"
            sub.text = "구절을 AI에 보낼 때 함께 전달되는 지시문입니다."
            edit.setText(AiStore.getVersePrompt(ctx))
        } else {
            title.text = "주보 요약 프롬프트"
            sub.text = "주보 말씀 구절을 정리·요약할 때 쓰는 지시문입니다."
            edit.setText(AiStore.getBulletinPrompt(ctx))
        }
        v.findViewById<View>(com.example.bibleapp.R.id.ai_prompt_default).setOnClickListener {
            edit.setText(if (verse) AiStore.DEF_VERSE_PROMPT else AiStore.DEF_BULLETIN_PROMPT)
        }
        v.findViewById<View>(com.example.bibleapp.R.id.ai_prompt_cancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(com.example.bibleapp.R.id.ai_prompt_save).setOnClickListener {
            val t = edit.text.toString()
            if (verse) AiStore.setVersePrompt(ctx, t) else AiStore.setBulletinPrompt(ctx, t)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showThemeDialog() {
        val ctx = requireContext()
        val cur = BookmarkStore.getThemeMode(ctx).coerceIn(0, 2)
        AlertDialog.Builder(ctx)
            .setTitle("화면 테마")
            .setSingleChoiceItems(THEME_LABELS, cur) { d, which ->
                BookmarkStore.setThemeMode(ctx, which)
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        else -> AppCompatDelegate.MODE_NIGHT_YES
                    }
                )
                d.dismiss()
                activity?.recreate()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showGestureDialog(slot: Int, title: String) {
        val ctx = requireContext()
        val labels = VerseAction.values().map { it.label }.toTypedArray()
        val cur = BookmarkStore.getGestureAction(ctx, slot).coerceIn(0, labels.size - 1)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(labels, cur) { d, which ->
                BookmarkStore.setGestureAction(ctx, slot, which)
                refreshValues()
                d.dismiss()
            }
            .setNeutralButton("기본값으로 초기화") { _, _ ->
                BookmarkStore.resetGestureAction(ctx, slot)
                refreshValues()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showSignatureDialog() {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            setText(BookmarkStore.getShareSignature(ctx))
            hint = "예: 영찬영하 Daddy 드림 (비우면 한영Bible)"
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(ctx)
            .setTitle("공유 장표 서명 문구")
            .setView(edit)
            .setPositiveButton("저장") { _, _ ->
                BookmarkStore.setShareSignature(ctx, edit.text.toString().trim())
                refreshValues()
            }
            .setNeutralButton("비우기") { _, _ ->
                BookmarkStore.setShareSignature(ctx, "")
                refreshValues()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showBulletinDialog() {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            setText(BookmarkStore.getBulletinUrl(ctx))
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(ctx).setTitle("이번주 주보 링크 변경").setView(edit)
            .setPositiveButton("저장") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isNotBlank()) BookmarkStore.setBulletinUrl(ctx, url)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun showUsageDialog() {
        val view = layoutInflater.inflate(com.example.bibleapp.R.layout.dialog_usage, null)
        AlertDialog.Builder(requireContext())
            .setTitle("사용법")
            .setView(view)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showLogDialog() {
        val log = com.example.bibleapp.util.AppLogger.readAll()
        AlertDialog.Builder(requireContext()).setTitle("진단 로그").setMessage(log)
            .setPositiveButton("공유") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, log) },
                    "로그 공유"))
            }
            .setNeutralButton("지우기") { _, _ -> com.example.bibleapp.util.AppLogger.clear() }
            .setNegativeButton("닫기", null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object {
        private val THEME_LABELS = arrayOf("시스템 설정 따름", "라이트(일반)", "다크")
    }
}
