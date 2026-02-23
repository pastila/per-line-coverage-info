package com.github.yakov255.perlinecoverageinfo

import com.intellij.coverage.CoverageEditorAnnotatorImpl
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

internal class PerLineCoverageEditorAnnotator(psiFile: PsiFile?, editor: Editor?) : CoverageEditorAnnotatorImpl(psiFile, editor)