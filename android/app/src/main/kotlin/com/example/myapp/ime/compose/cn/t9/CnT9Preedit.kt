package com.example.myapp.ime.compose.cn.t9

data class CnT9PreeditSegment(
    val text: String,
    val isFocused: Boolean = false,
    val isLocked: Boolean = false
)

data class CnT9PreeditModel(
    val text: String?,
    val coreText: String?,
    val segments: List<CnT9PreeditSegment>,
    val focusedSegmentIndex: Int?,
    val enterCommitText: String?
)
