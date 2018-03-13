/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jvm.compiler

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.util.Function
import com.intellij.util.SmartList
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.KtStubLightClass
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.load.java.components.FilesByFacadeFqNameIndexer
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.lazy.ResolveSessionUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope

class CliKotlinAsJavaSupport(
    project: Project,
    private val traceHolder: CliTraceHolder
) : KotlinAsJavaSupport() {
    private val psiManager = PsiManager.getInstance(project)

    override fun getFacadeClassesInPackage(packageFqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        return findFacadeFilesInPackage(packageFqName, scope)
            .groupBy { it.javaFileFacadeFqName }
            .mapNotNull { (fqName, files) ->
                KtStubLightClass.ForFacade(
                    fqName,
                    files,
                    psiManager
                )
            }
    }

    override fun getFacadeNames(packageFqName: FqName, scope: GlobalSearchScope): Collection<String> {
        return findFacadeFilesInPackage(packageFqName, scope)
            .map { it.javaFileFacadeFqName.shortName().asString() }
    }

    private fun findFacadeFilesInPackage(
        packageFqName: FqName,
        scope: GlobalSearchScope
    ) = traceHolder.bindingContext.get(FilesByFacadeFqNameIndexer.FACADE_FILES_BY_PACKAGE_NAME, packageFqName)
        ?.filter { PsiSearchScopeUtil.isInScope(scope, it) }
        .orEmpty()

    override fun getFacadeClasses(facadeFqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        val filesForFacade = findFilesForFacade(facadeFqName, scope)
        if (filesForFacade.isEmpty()) return emptyList()

        return listOf(
            KtStubLightClass.ForFacade(
                facadeFqName,
                filesForFacade,
                psiManager
            )
        )
    }

    override fun getScriptClasses(scriptFqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        if (scriptFqName.isRoot) {
            return emptyList()
        }

        return findFilesForPackage(scriptFqName.parent(), scope).mapNotNull { file ->
            file.script?.takeIf { it.fqName == scriptFqName }?.let { it -> getLightClassForScript(it) }
        }
    }

    override fun getKotlinInternalClasses(fqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        //
        return emptyList()
    }

    override fun findFilesForFacade(facadeFqName: FqName, scope: GlobalSearchScope): Collection<KtFile> {
        if (facadeFqName.isRoot) return emptyList()

        return traceHolder.bindingContext.get(FilesByFacadeFqNameIndexer.FACADE_FILES_BY_FQ_NAME, facadeFqName)?.filter {
            PsiSearchScopeUtil.isInScope(scope, it)
        } ?: emptyList()
    }


    override fun findClassOrObjectDeclarationsInPackage(
        packageFqName: FqName, searchScope: GlobalSearchScope
    ): Collection<KtClassOrObject> {
        val files = findFilesForPackage(packageFqName, searchScope)
        val result = SmartList<KtClassOrObject>()
        for (file in files) {
            for (declaration in file.declarations) {
                if (declaration is KtClassOrObject) {
                    result.add(declaration)
                }
            }
        }
        return result
    }

    override fun packageExists(fqName: FqName, scope: GlobalSearchScope): Boolean {
        return !traceHolder.module.getPackage(fqName).isEmpty()
    }

    override fun getSubPackages(fqn: FqName, scope: GlobalSearchScope): Collection<FqName> {
        val packageView = traceHolder.module.getPackage(fqn)
        val members = packageView.memberScope.getContributedDescriptors(
            DescriptorKindFilter.PACKAGES,
            MemberScope.ALL_NAME_FILTER
        )
        return ContainerUtil.mapNotNull(
            members,
            object : Function<DeclarationDescriptor, FqName> {
                override fun `fun`(member: DeclarationDescriptor): FqName? {
                    if (member is PackageViewDescriptor) {
                        return member.fqName
                    }
                    return null
                }
            })
    }

    override fun getLightClass(classOrObject: KtClassOrObject): KtLightClass? =
        KtStubLightClass.ForClassOrObject(classOrObject, psiManager)

    override fun getLightClassForScript(script: KtScript): KtLightClass? =
        KtStubLightClass.ForScript(script, psiManager)


    override fun findClassOrObjectDeclarations(fqName: FqName, searchScope: GlobalSearchScope): Collection<KtClassOrObject> {
        return ResolveSessionUtils.getClassDescriptorsByFqName(traceHolder.module, fqName).mapNotNull {
            val element = DescriptorToSourceUtils.getSourceFromDescriptor(it)
            if (element is KtClassOrObject && PsiSearchScopeUtil.isInScope(
                    searchScope,
                    element
                )
            ) {
                element
            } else null
        }
    }

    override fun findFilesForPackage(fqName: FqName, searchScope: GlobalSearchScope): Collection<KtFile> {
        return traceHolder.bindingContext.get(BindingContext.PACKAGE_TO_FILES, fqName)?.filter {
            PsiSearchScopeUtil.isInScope(searchScope, it)
        } ?: emptyList()
    }
}