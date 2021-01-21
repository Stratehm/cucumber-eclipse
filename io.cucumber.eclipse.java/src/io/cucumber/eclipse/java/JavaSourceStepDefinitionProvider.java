package io.cucumber.eclipse.java;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;

import io.cucumber.eclipse.editor.steps.ExpressionDefinition;
import io.cucumber.eclipse.editor.steps.StepDefinition;
import io.cucumber.eclipse.editor.steps.StepParameter;

/**
 * Scans the source files of a project for step-definitions
 * 
 * @author christoph
 *
 */
public class JavaSourceStepDefinitionProvider extends JavaStepDefinitionsProvider {

	private static final String CUCUMBER_API_JAVA = "cucumber.api.java.";
	private static final String CUCUMBER_API_JAVA8 = "cucumber.api.java8.";
	private static final String IO_CUCUMBER_JAVA = "io.cucumber.java.";
	private static final String IO_CUCUMBER_JAVA8 = "io.cucumber.java8.";

	@Override
	public Collection<StepDefinition> findStepDefinitions(IResource stepDefinitionResource, IProgressMonitor monitor)
			throws CoreException {
		IJavaProject javaProject = getJavaProject(stepDefinitionResource);
		if (javaProject != null) {
			if (stepDefinitionResource instanceof IProject) {
				List<StepDefinition> allProjectSteps = new ArrayList<>();
				List<ICompilationUnit> units = new ArrayList<>();
				for (IPackageFragment fragment : javaProject.getPackageFragments()) {
					for (ICompilationUnit unit : fragment.getCompilationUnits()) {
						units.add(unit);
					}
				}
				SubMonitor subMonitor = SubMonitor.convert(monitor, units.size() * 100);
				for (ICompilationUnit unit : units) {
					allProjectSteps.addAll(getCukeSteps(unit, subMonitor.split(100)));
				}
				return allProjectSteps;
			} else {
				IJavaElement javaElement = JavaCore.create(stepDefinitionResource);
				if (javaElement instanceof ICompilationUnit) {
					ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
					return getCukeSteps(compilationUnit, monitor);
				}
			}
		}
		return Collections.emptySet();
	}

	// From Java-Source-File(.java) : Collect All Steps as List based on
	// Cucumber-Annotations

	private List<StepDefinition> getCukeSteps(ICompilationUnit compilationUnit, IProgressMonitor progressMonitor)
			throws JavaModelException, CoreException {

		//// TODO
////	if (CucumberJavaPreferences.isUseStepDefinitionsFilters()) {
////		String[] filters = CucumberJavaPreferences.getStepDefinitionsFilters();
////		CompilationUnitStepDefinitionsPreferencesFilter filter = new CompilationUnitStepDefinitionsPreferencesFilter(
////				filters);
////		if (!filter.accept(compilationUnit)) {
////			// skip
////			return new HashSet<StepDefinition>();
////		}
////	}
		long start = System.currentTimeMillis();

		List<StepDefinition> steps = new ArrayList<StepDefinition>();

		try {
			List<CucumberAnnotation> importedAnnotations = new ArrayList<CucumberAnnotation>();
			IImportDeclaration[] allimports = compilationUnit.getImports();

			for (IImportDeclaration decl : allimports) {

				// Match pre-5.x Package name
				Matcher m = cucumberApiAnnotationMatcher.matcher(decl.getElementName());
				if (m.find()) {
					if ("*".equals(m.group(2))) {
						importedAnnotations.addAll(getAllAnnotationsInPackage(compilationUnit.getJavaProject(),
								CUCUMBER_API_JAVA + m.group(1), m.group(1), progressMonitor));
					} else {
						importedAnnotations.add(new CucumberAnnotation(m.group(2), m.group(1)));
					}
				}

				// Match 5.x+ Package name
				m = ioCucumberAnnotationMatcher.matcher(decl.getElementName());
				if (m.find()) {
					if ("*".equals(m.group(2))) {
						importedAnnotations.addAll(getAllAnnotationsInPackage(compilationUnit.getJavaProject(),
								IO_CUCUMBER_JAVA + m.group(1), m.group(1), progressMonitor));
					} else {
						importedAnnotations.add(new CucumberAnnotation(m.group(2), m.group(1)));
					}
				}

			}

			List<MethodDeclaration> methodDeclList = null;
			JavaParser javaParser = null;
			for (IType t : compilationUnit.getTypes()) {
				// collect all steps from java8 lambdas
				IResource resource = compilationUnit.getResource();
				for (IType ifType : t.newTypeHierarchy(progressMonitor).getAllInterfaces()) {

					if (ifType.isInterface() && (ifType.getFullyQualifiedName().startsWith(CUCUMBER_API_JAVA8)
							|| ifType.getFullyQualifiedName().startsWith(IO_CUCUMBER_JAVA8))) {
						String[] superInterfaceNames = ifType.getSuperInterfaceNames();
						for (String superIfName : superInterfaceNames) {
							if (superIfName.endsWith(".LambdaGlueBase")) {
								// we found a possible interface, now try to
								// load the language...
								// String lang =
								// ifType.getElementName().toLowerCase();
								// init if not done in previous step..
								if (javaParser == null) {
									javaParser = new JavaParser(compilationUnit, progressMonitor);
								}
								if (methodDeclList == null) {
									methodDeclList = javaParser.getAllMethods();
								}
								Set<String> keyWords = new HashSet<String>();
								for (IMethod method : ifType.getMethods()) {
									keyWords.add(method.getElementName());
								}
								List<MethodDefinition> methodDefList = new ArrayList<MethodDefinition>();
								// Visiting Methods/Constructors
								for (MethodDeclaration method : methodDeclList) {

									// Get Method/Constructor-Block{...}
									if (isCukeLambdaExpr(method, keyWords)) {
										// Collect method-body as List of
										// Statements
										@SuppressWarnings("unchecked")
										List<Statement> statementList = method.getBody().statements();
										if (!statementList.isEmpty()) {
											MethodDefinition definition = new MethodDefinition(method.getName(),
													method.getReturnType2(), statementList);
											methodDefList.add(definition);
											// definition.setJava8CukeLang(lang);
										}
									}
								}
								// Iterate MethodDefinition
								for (MethodDefinition method : methodDefList) {
									// Iterate Method-Statements
									for (Statement statement : method.getMethodBodyList()) {
										String lambdaStep = method.getLambdaStep(statement, keyWords);
										if (lambdaStep == null) {
											continue;
										}
										int lineNumber = javaParser.getLineNumber(statement);
										ExpressionDefinition expression;
										expression = new ExpressionDefinition(lambdaStep, method.getCukeLang());
										StepDefinition step = new StepDefinition(ifType.getHandleIdentifier(),
												StepDefinition.NO_LABEL, expression, resource, lineNumber,
												method.getMethodName(), t.getPackageFragment().getElementName(),
												new StepParameter[] {});
										steps.add(step);
									}
								}
							}
						}
					}
				}
				// Collect all steps from Annotations used in the methods as per
				// imported
				// Annotations
				for (IMethod method : t.getMethods()) {
					for (IAnnotation annotation : method.getAnnotations()) {
						CucumberAnnotation cukeAnnotation = getCukeAnnotation(importedAnnotations, annotation);
						if (cukeAnnotation != null) {
							int lineNumber = getLineNumber(compilationUnit, annotation);
							ExpressionDefinition expression;
							expression = new ExpressionDefinition(getAnnotationText(annotation),
									cukeAnnotation.getLang());
							StepDefinition step = new StepDefinition(method.getHandleIdentifier(),
									StepDefinition.NO_LABEL, expression, resource, lineNumber, method.getElementName(),
									t.getPackageFragment().getElementName(), getParameter(method));
							steps.add(step);
						}
					}

				}
			}

		} catch (JavaModelException e) {
			System.out.println("Warning: could not process " + compilationUnit.getElementName());
		}
		long end = System.currentTimeMillis();
		System.out.println("getCukeSteps " + compilationUnit.getJavaProject().getElementName() + ": "
				+ compilationUnit.getElementName() + " " + (end - start) + " ms.");

		return steps;
	}

	@Override
	public boolean support(IResource resource) throws CoreException {
		IJavaElement javaElement = JavaCore.create(resource);
		if (javaElement instanceof ICompilationUnit) {
			return true;
		}
		return super.support(resource);
	}

	/**
	 * @param method
	 * @param i18n
	 * @return boolean
	 */
	private static boolean isCukeLambdaExpr(MethodDeclaration method, Set<String> keywords) {
		@SuppressWarnings("unchecked")
		List<Statement> statements = method.getBody().statements();
		for (Statement statement : statements) {
			if (statement instanceof ExpressionStatement) {
				ExpressionStatement expressionStatement = (ExpressionStatement) statement;
				Expression expression = expressionStatement.getExpression();
				if (expression instanceof MethodInvocation) {
					String identifier = ((MethodInvocation) expression).getName().getIdentifier();
					if (keywords.contains(identifier)) {
						// we found a lamda
						return true;
					}
				}
			}

		}
		return false;
	}


}
