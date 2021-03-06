/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.filters.classes;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.filters.ElementFilter;
import com.intellij.util.ReflectionUtil;
import org.jetbrains.annotations.NonNls;

public class EnumOrAnnotationTypeFilter implements ElementFilter{

  @Override
  public boolean isClassAcceptable(Class hintClass){
    return ReflectionUtil.isAssignable(PsiClass.class, hintClass);
  }

  @Override
  public boolean isAcceptable(Object element, PsiElement context){
    if(element instanceof PsiClass){
      return ((PsiClass)element).isEnum() || ((PsiClass)element).isAnnotationType();
    }
    return false;
  }

  @NonNls
  public String toString(){
    return "enum or annotation type";
  }
}
