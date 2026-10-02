package dev.conduit.llm

/**
 * Fill-in-the-middle prompt formats of the open code models, for servers that take a raw prompt
 * rather than a prefix/suffix pair. Matched on the model name, case-insensitively.
 */
object FimTemplates {
    /** A model family's special tokens. */
    data class Template(
        val prefix: String,
        val suffix: String,
        val middle: String,
        /** Tokens the model emits when it is done; they end generation and are stripped. */
        val stop: List<String>,
        /** Some families put the suffix before the prefix (SPM order). */
        val suffixFirst: Boolean = false,
    )

    private val qwen =
        Template(
            "<|fim_prefix|>",
            "<|fim_suffix|>",
            "<|fim_middle|>",
            listOf("<|endoftext|>", "<|fim_pad|>", "<|repo_name|>", "<|file_sep|>"),
        )
    private val codeLlama = Template("<PRE> ", " <SUF>", " <MID>", listOf("<EOT>", "</s>"))
    private val deepSeek = Template("<｜fim▁begin｜>", "<｜fim▁hole｜>", "<｜fim▁end｜>", listOf("<｜end▁of▁sentence｜>"))
    private val starCoder = Template("<fim_prefix>", "<fim_suffix>", "<fim_middle>", listOf("<|endoftext|>", "<file_sep>"))
    private val codeGemma = Template("<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>", listOf("<|file_separator|>", "<eos>"))
    private val codestral = Template("[SUFFIX]", "[PREFIX]", "", listOf("</s>"), suffixFirst = true)
    private val granite = Template("<fim_prefix>", "<fim_suffix>", "<fim_middle>", listOf("<|endoftext|>"))

    private val families =
        listOf(
            "qwen" to qwen,
            "codellama" to codeLlama,
            "code-llama" to codeLlama,
            "deepseek" to deepSeek,
            "starcoder" to starCoder,
            "codegemma" to codeGemma,
            "codestral" to codestral,
            "granite" to granite,
            "stable-code" to starCoder,
            "stablecode" to starCoder,
        )

    /** The template for [model], or null when the family is unknown. */
    fun forModel(model: String): Template? {
        val name = model.lowercase()
        return families.firstOrNull { (family, _) -> name.contains(family) }?.second
    }

    /** The raw prompt for [model]; falls back to the StarCoder-style tokens most servers accept. */
    fun format(
        model: String,
        prefix: String,
        suffix: String,
    ): String {
        val t = forModel(model) ?: starCoder
        return if (t.suffixFirst) {
            "${t.prefix}$suffix${t.suffix}$prefix${t.middle}"
        } else {
            "${t.prefix}$prefix${t.suffix}$suffix${t.middle}"
        }
    }

    /** Stop sequences for [model]: its family's end tokens, or a generic set. */
    fun stops(model: String): List<String> = (forModel(model) ?: starCoder).stop

    /**
     * The prompt for chat-style APIs without native FIM: instructs the model to return only the
     * missing text. Used with Anthropic and Gemini.
     */
    fun chatPrompt(request: FimRequest): String =
        buildString {
            append("You are a code completion engine. Output ONLY the code that goes at the <CURSOR> position, ")
            append("with no explanation, no markdown fences and no repetition of the surrounding code. ")
            append("Stop when the statement or block is complete.\n\n")
            if (request.filePath.isNotEmpty()) append("File: ").append(request.filePath).append('\n')
            if (request.language.isNotEmpty()) append("Language: ").append(request.language).append('\n')
            append("\n```\n")
                .append(request.prefix)
                .append("<CURSOR>")
                .append(request.suffix)
                .append("\n```")
        }
}
