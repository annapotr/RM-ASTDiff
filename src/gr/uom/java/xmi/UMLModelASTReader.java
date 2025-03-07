package gr.uom.java.xmi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

import gr.uom.java.xmi.diff.UMLModelDiff;
import jdt.JdtVisitor;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import gr.uom.java.xmi.LocationInfo.CodeElementType;
import gr.uom.java.xmi.decomposition.OperationBody;
import gr.uom.java.xmi.decomposition.VariableDeclaration;
import org.refactoringminer.api.RefactoringMinerTimedOutException;
import org.refactoringminer.rm1.ProjectData;
import tree.TreeContext;

public class UMLModelASTReader {
	private static final String FREE_MARKER_GENERATED = "generated using freemarker";
	private static final String FREE_MARKER_GENERATED_2 = "generated using FreeMarker";
	private static final String systemFileSeparator = Matcher.quoteReplacement(File.separator);
	private UMLModel umlModel;
	private Map<String, String> fileContents;

	public UMLModelASTReader(Map<String, String> javaFileContents, Set<String> repositoryDirectories) {
		this.umlModel = new UMLModel(repositoryDirectories);
		processJavaFileContents(javaFileContents);
	}

	private void processJavaFileContents(Map<String, String> javaFileContents) {
		this.fileContents = javaFileContents;
		ASTParser parser = ASTParser.newParser(AST.JLS17);
		for(String filePath : javaFileContents.keySet()) {
			Map<String, String> options = JavaCore.getOptions();
			options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
			options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
			options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_8);
			parser.setCompilerOptions(options);
			parser.setResolveBindings(false);
			parser.setKind(ASTParser.K_COMPILATION_UNIT);
			parser.setStatementsRecovery(true);

			String javaFileContent = javaFileContents.get(filePath);
			parser.setSource(javaFileContent.toCharArray());
			if((javaFileContent.contains(FREE_MARKER_GENERATED) || javaFileContent.contains(FREE_MARKER_GENERATED_2)) &&
					!javaFileContent.contains("private static final String FREE_MARKER_GENERATED = \"generated using freemarker\";")) {
				continue;
			}
			try {
				CompilationUnit compilationUnit = (CompilationUnit)parser.createAST(null);
				IScanner scanner = ToolFactory.createScanner(true, false, false, false);
				scanner.setSource(javaFileContent.toCharArray());
				JdtVisitor visitor = new JdtVisitor(scanner);
				compilationUnit.accept(visitor);

				processCompilationUnit(filePath, compilationUnit, javaFileContent);
				TreeContext treeContext = visitor.getTreeContext();
				treeContext.setUmlCommentList(extractInternalComments(compilationUnit, filePath, javaFileContent));
				treeContext.setFilename(filePath);
				this.getUmlModel().addTreeContext(filePath,treeContext); //TODO:  Use fullpath to avoid errors while two files have the same name
			}
			catch(Exception e) {
				//e.printStackTrace();
			}
		}
	}
	public static ProjectData makeProjectData(String dir1, String dir2) throws IOException, RefactoringMinerTimedOutException {
		UMLModelASTReader umlModelASTReader1 = new UMLModelASTReader(new File(dir1));
		UMLModelASTReader umlModelASTReader2 = new UMLModelASTReader(new File(dir2));
		long RM_started =  System.currentTimeMillis();
		UMLModelDiff modelDiff = umlModelASTReader1.getUmlModel().diff(umlModelASTReader2.getUmlModel());
		long RM_finished =  System.currentTimeMillis();
		System.out.println("RefactoringMiner ModelDiff execution: " + (RM_finished - RM_started)/ 1000 + " seconds");
		ProjectData projectData = new ProjectData();
		projectData.setUmlModelDiff(modelDiff);
		projectData.setFileContentsBefore(umlModelASTReader1.getFileContents());
		projectData.setFileContentsCurrent(umlModelASTReader2.getFileContents());
		return projectData;

	}

	public static ProjectData makeProjectData_fromFiles(String file1, String file2) throws IOException, RefactoringMinerTimedOutException {
		//TODO, RefactoringMiner support for this one
		String fullpath1 = file1.replaceAll("/", systemFileSeparator);
		String contents1 = FileUtils.readFileToString(new File(fullpath1));
		Map<String,String> dir1info = new HashMap<>();
		dir1info.put(fullpath1,contents1);

		String fullpath2 = file2.replaceAll("/", systemFileSeparator);
		String contents2 = FileUtils.readFileToString(new File(fullpath2));
		Map<String,String> dir2info = new HashMap<>();
		dir2info.put(fullpath2,contents2);

		UMLModelASTReader umlModelASTReader1 = new UMLModelASTReader(dir1info,null);
		UMLModelASTReader umlModelASTReader2 = new UMLModelASTReader(dir2info,null);

		UMLModelDiff modelDiff = umlModelASTReader1.getUmlModel().diff(umlModelASTReader2.getUmlModel());
		ProjectData projectData = new ProjectData();
		projectData.setUmlModelDiff(modelDiff);
		projectData.setFileContentsBefore(umlModelASTReader1.getFileContents());
		projectData.setFileContentsCurrent(umlModelASTReader2.getFileContents());
		return projectData;

	}

	private Map<String, String> getFileContents() {
		return this.fileContents;
	}

	public UMLModelASTReader(File rootFolder) throws IOException {
		List<String> javaFilePaths = getJavaFilePaths(rootFolder);
		Map<String, String> javaFileContents = new LinkedHashMap<String, String>();
		Set<String> repositoryDirectories = new LinkedHashSet<String>();
		for(String path : javaFilePaths) {
			String fullPath = rootFolder + File.separator + path.replaceAll("/", systemFileSeparator);
			String contents = FileUtils.readFileToString(new File(fullPath));
			javaFileContents.put(path, contents);
			String directory = new String(path);
			while(directory.contains("/")) {
				directory = directory.substring(0, directory.lastIndexOf("/"));
				repositoryDirectories.add(directory);
			}
		}
		this.umlModel = new UMLModel(rootFolder,repositoryDirectories);
		processJavaFileContents(javaFileContents);
	}

	private static List<String> getJavaFilePaths(File folder) throws IOException {
		Stream<Path> walk = Files.walk(Paths.get(folder.toURI()));
		List<String> paths = walk.map(x -> x.toString())
				.filter(f -> f.endsWith(".java"))
				.map(x -> x.substring(folder.getPath().length()+1).replaceAll(systemFileSeparator, "/"))
				.collect(Collectors.toList());
		walk.close();
		return paths;
	}

	public UMLModel getUmlModel() {
		return this.umlModel;
	}

	protected void processCompilationUnit(String sourceFilePath, CompilationUnit compilationUnit, String javaFileContent) {
		List<UMLComment> comments = extractInternalComments(compilationUnit, sourceFilePath, javaFileContent);
		PackageDeclaration packageDeclaration = compilationUnit.getPackage();
		String packageName = null;
		UMLJavadoc packageDoc = null;
		if(packageDeclaration != null) {
			packageName = packageDeclaration.getName().getFullyQualifiedName();
			packageDoc = generateJavadoc(compilationUnit, sourceFilePath, packageDeclaration.getJavadoc());
		}
		else {
			packageName = "";
		}
		
		List<ImportDeclaration> imports = compilationUnit.imports();
		List<String> importedTypes = new ArrayList<String>();
		for(ImportDeclaration importDeclaration : imports) {
			importedTypes.add(importDeclaration.getName().getFullyQualifiedName());
		}
		List<AbstractTypeDeclaration> topLevelTypeDeclarations = compilationUnit.types();
        for(AbstractTypeDeclaration abstractTypeDeclaration : topLevelTypeDeclarations) {
        	if(abstractTypeDeclaration instanceof TypeDeclaration) {
        		TypeDeclaration topLevelTypeDeclaration = (TypeDeclaration)abstractTypeDeclaration;
        		processTypeDeclaration(compilationUnit, topLevelTypeDeclaration, packageName, sourceFilePath, importedTypes, packageDoc, comments);
        	}
        	else if(abstractTypeDeclaration instanceof EnumDeclaration) {
        		EnumDeclaration enumDeclaration = (EnumDeclaration)abstractTypeDeclaration;
        		processEnumDeclaration(compilationUnit, enumDeclaration, packageName, sourceFilePath, importedTypes, packageDoc, comments);
        	}
        }
	}

	private List<UMLComment> extractInternalComments(CompilationUnit cu, String sourceFile, String javaFileContent) {
		List<Comment> astComments = cu.getCommentList();
		List<UMLComment> comments = new ArrayList<UMLComment>();
		for(Comment comment : astComments) {
			LocationInfo locationInfo = null;
			if(comment.isLineComment()) {
				locationInfo = generateLocationInfo(cu, sourceFile, comment, CodeElementType.LINE_COMMENT);
			}
			else if(comment.isBlockComment()) {
				locationInfo = generateLocationInfo(cu, sourceFile, comment, CodeElementType.BLOCK_COMMENT);
			}
			if(locationInfo != null) {
				int start = comment.getStartPosition();
				int end = start + comment.getLength();
				String text = javaFileContent.substring(start, end);
				UMLComment umlComment = new UMLComment(text, locationInfo);
				comments.add(umlComment);
			}
		}
		return comments;
	}

	private void distributeComments(List<UMLComment> compilationUnitComments, LocationInfo codeElementLocationInfo, List<UMLComment> codeElementComments) {
		ListIterator<UMLComment> listIterator = compilationUnitComments.listIterator(compilationUnitComments.size());
		while(listIterator.hasPrevious()) {
			UMLComment comment = listIterator.previous();
			LocationInfo commentLocationInfo = comment.getLocationInfo();
			if(codeElementLocationInfo.subsumes(commentLocationInfo) ||
					codeElementLocationInfo.sameLine(commentLocationInfo) ||
					(codeElementLocationInfo.nextLine(commentLocationInfo) && !codeElementLocationInfo.getCodeElementType().equals(CodeElementType.ANONYMOUS_CLASS_DECLARATION)) ||
					(codeElementComments.size() > 0 && codeElementComments.get(0).getLocationInfo().nextLine(commentLocationInfo))) {
				codeElementComments.add(0, comment);
			}
		}
		compilationUnitComments.removeAll(codeElementComments);
	}

	private UMLJavadoc generateJavadoc(CompilationUnit cu, BodyDeclaration bodyDeclaration, String sourceFile) {
		Javadoc javaDoc = bodyDeclaration.getJavadoc();
		return generateJavadoc(cu, sourceFile, javaDoc);
	} 

	private UMLJavadoc generateJavadoc(CompilationUnit cu, String sourceFile, Javadoc javaDoc) {
		UMLJavadoc doc = null;
		if(javaDoc != null) {
			LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, javaDoc, CodeElementType.JAVADOC);
			doc = new UMLJavadoc(locationInfo);
			List<TagElement> tags = javaDoc.tags();
			for(TagElement tag : tags) {
				UMLTagElement tagElement = new UMLTagElement(tag.getTagName());
				List fragments = tag.fragments();
				for(Object docElement : fragments) {
					tagElement.addFragment(docElement.toString());
				}
				doc.addTag(tagElement);
			}
		}
		return doc;
	}

	private void processEnumDeclaration(CompilationUnit cu, EnumDeclaration enumDeclaration, String packageName, String sourceFile,
			List<String> importedTypes, UMLJavadoc packageDoc, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, enumDeclaration, sourceFile);
		if(javadoc != null && javadoc.containsIgnoreCase(FREE_MARKER_GENERATED)) {
			return;
		}
		String className = enumDeclaration.getName().getFullyQualifiedName();
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, enumDeclaration, CodeElementType.TYPE_DECLARATION);
		UMLClass umlClass = new UMLClass(packageName, className, locationInfo, enumDeclaration.isPackageMemberTypeDeclaration(), importedTypes);
		umlClass.setJavadoc(javadoc);
		if(enumDeclaration.isPackageMemberTypeDeclaration()) {
			umlClass.setPackageDeclarationJavadoc(packageDoc);
			for(UMLComment comment : comments) {
				if(comment.getLocationInfo().getStartLine() == 1) {
					umlClass.getPackageDeclarationComments().add(comment);
				}
			}
		}
		umlClass.setEnum(true);
		
		List<Type> superInterfaceTypes = enumDeclaration.superInterfaceTypes();
    	for(Type interfaceType : superInterfaceTypes) {
    		UMLType umlType = UMLType.extractTypeObject(cu, sourceFile, interfaceType, 0);
    		UMLRealization umlRealization = new UMLRealization(umlClass, umlType.getClassType());
    		umlClass.addImplementedInterface(umlType);
    		getUmlModel().addRealization(umlRealization);
    	}
    	
    	List<EnumConstantDeclaration> enumConstantDeclarations = enumDeclaration.enumConstants();
    	for(EnumConstantDeclaration enumConstantDeclaration : enumConstantDeclarations) {
			processEnumConstantDeclaration(cu, enumConstantDeclaration, sourceFile, umlClass, comments);
		}
		
		processModifiers(cu, sourceFile, enumDeclaration, umlClass);
		
		Map<BodyDeclaration, VariableDeclarationContainer> map = processBodyDeclarations(cu, enumDeclaration, packageName, sourceFile, importedTypes, umlClass, packageDoc, comments);
		
		processAnonymousClassDeclarations(cu, enumDeclaration, packageName, sourceFile, className, umlClass);

		for(BodyDeclaration declaration : map.keySet()) {
			if(declaration instanceof MethodDeclaration) {
				UMLOperation operation = (UMLOperation) map.get(declaration);
				processMethodBody(cu, sourceFile, (MethodDeclaration) declaration, operation);
			}
			else if(declaration instanceof Initializer) {
				UMLInitializer initializer = (UMLInitializer) map.get(declaration);
				processInitializerBody(cu, sourceFile, (Initializer) declaration, initializer);
			}
		}
		
		this.getUmlModel().addClass(umlClass);
		distributeComments(comments, locationInfo, umlClass.getComments());
	}

	private Map<BodyDeclaration, VariableDeclarationContainer> processBodyDeclarations(CompilationUnit cu, AbstractTypeDeclaration abstractTypeDeclaration, String packageName,
			String sourceFile, List<String> importedTypes, UMLClass umlClass, UMLJavadoc packageDoc, List<UMLComment> comments) {
		Map<BodyDeclaration, VariableDeclarationContainer> map = new LinkedHashMap<>();
		List<BodyDeclaration> bodyDeclarations = abstractTypeDeclaration.bodyDeclarations();
		for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
			if(bodyDeclaration instanceof FieldDeclaration) {
				FieldDeclaration fieldDeclaration = (FieldDeclaration)bodyDeclaration;
				List<UMLAttribute> attributes = processFieldDeclaration(cu, fieldDeclaration, umlClass.isInterface(), sourceFile, comments);
	    		for(UMLAttribute attribute : attributes) {
	    			attribute.setClassName(umlClass.getName());
	    			umlClass.addAttribute(attribute);
	    		}
			}
			else if(bodyDeclaration instanceof MethodDeclaration) {
				MethodDeclaration methodDeclaration = (MethodDeclaration)bodyDeclaration;
				UMLOperation operation = processMethodDeclaration(cu, methodDeclaration, packageName, umlClass.isInterface(), sourceFile, comments);
	    		operation.setClassName(umlClass.getName());
	    		umlClass.addOperation(operation);
	    		map.put(methodDeclaration, operation);
			}
			else if(bodyDeclaration instanceof Initializer) {
				Initializer initializer = (Initializer)bodyDeclaration;
				UMLInitializer umlInitializer = processInitializer(cu, initializer, packageName, false, sourceFile, comments);
				umlInitializer.setClassName(umlClass.getName());
				umlClass.addInitializer(umlInitializer);
				map.put(initializer, umlInitializer);
			}
			else if(bodyDeclaration instanceof TypeDeclaration) {
				TypeDeclaration typeDeclaration = (TypeDeclaration)bodyDeclaration;
				processTypeDeclaration(cu, typeDeclaration, umlClass.getName(), sourceFile, importedTypes, packageDoc, comments);
			}
			else if(bodyDeclaration instanceof EnumDeclaration) {
				EnumDeclaration enumDeclaration = (EnumDeclaration)bodyDeclaration;
				processEnumDeclaration(cu, enumDeclaration, umlClass.getName(), sourceFile, importedTypes, packageDoc, comments);
			}
		}
		return map;
	}

	private void processTypeDeclaration(CompilationUnit cu, TypeDeclaration typeDeclaration, String packageName, String sourceFile,
			List<String> importedTypes, UMLJavadoc packageDoc, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, typeDeclaration, sourceFile);
		if(javadoc != null && javadoc.containsIgnoreCase(FREE_MARKER_GENERATED)) {
			return;
		}
		String className = typeDeclaration.getName().getFullyQualifiedName();
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, typeDeclaration, CodeElementType.TYPE_DECLARATION);
		UMLClass umlClass = new UMLClass(packageName, className, locationInfo, typeDeclaration.isPackageMemberTypeDeclaration(), importedTypes);
		umlClass.setJavadoc(javadoc);
		if(typeDeclaration.isPackageMemberTypeDeclaration()) {
			umlClass.setPackageDeclarationJavadoc(packageDoc);
			for(UMLComment comment : comments) {
				if(comment.getLocationInfo().getStartLine() == 1) {
					umlClass.getPackageDeclarationComments().add(comment);
				}
			}
		}
		if(typeDeclaration.isInterface()) {
			umlClass.setInterface(true);
    	}
    	
    	processModifiers(cu, sourceFile, typeDeclaration, umlClass);
		
    	List<TypeParameter> typeParameters = typeDeclaration.typeParameters();
		for(TypeParameter typeParameter : typeParameters) {
			UMLTypeParameter umlTypeParameter = new UMLTypeParameter(typeParameter.getName().getFullyQualifiedName(),
					generateLocationInfo(cu, sourceFile, typeParameter, CodeElementType.TYPE_PARAMETER));
			List<Type> typeBounds = typeParameter.typeBounds();
			for(Type type : typeBounds) {
				umlTypeParameter.addTypeBound(UMLType.extractTypeObject(cu, sourceFile, type, 0));
			}
			List<IExtendedModifier> typeParameterExtendedModifiers = typeParameter.modifiers();
			for(IExtendedModifier extendedModifier : typeParameterExtendedModifiers) {
				if(extendedModifier.isAnnotation()) {
					Annotation annotation = (Annotation)extendedModifier;
					umlTypeParameter.addAnnotation(new UMLAnnotation(cu, sourceFile, annotation));
				}
			}
    		umlClass.addTypeParameter(umlTypeParameter);
    	}
    	
    	Type superclassType = typeDeclaration.getSuperclassType();
    	if(superclassType != null) {
    		UMLType umlType = UMLType.extractTypeObject(cu, sourceFile, superclassType, 0);
    		UMLGeneralization umlGeneralization = new UMLGeneralization(umlClass, umlType.getClassType());
    		umlClass.setSuperclass(umlType);
    		getUmlModel().addGeneralization(umlGeneralization);
    	}
    	
    	List<Type> superInterfaceTypes = typeDeclaration.superInterfaceTypes();
    	for(Type interfaceType : superInterfaceTypes) {
    		UMLType umlType = UMLType.extractTypeObject(cu, sourceFile, interfaceType, 0);
    		UMLRealization umlRealization = new UMLRealization(umlClass, umlType.getClassType());
    		umlClass.addImplementedInterface(umlType);
    		getUmlModel().addRealization(umlRealization);
    	}
    	
    	Map<BodyDeclaration, VariableDeclarationContainer> map = processBodyDeclarations(cu, typeDeclaration, packageName, sourceFile, importedTypes, umlClass, packageDoc, comments);
    	
    	processAnonymousClassDeclarations(cu, typeDeclaration, packageName, sourceFile, className, umlClass);
    	
    	for(BodyDeclaration declaration : map.keySet()) {
    		if(declaration instanceof MethodDeclaration) {
				UMLOperation operation = (UMLOperation) map.get(declaration);
				processMethodBody(cu, sourceFile, (MethodDeclaration) declaration, operation);
			}
			else if(declaration instanceof Initializer) {
				UMLInitializer initializer = (UMLInitializer) map.get(declaration);
				processInitializerBody(cu, sourceFile, (Initializer) declaration, initializer);
			}
    	}
    	
    	this.getUmlModel().addClass(umlClass);
		distributeComments(comments, locationInfo, umlClass.getComments());
	}

	private void processAnonymousClassDeclarations(CompilationUnit cu, AbstractTypeDeclaration typeDeclaration,
			String packageName, String sourceFile, String className, UMLClass umlClass) {
		AnonymousClassDeclarationVisitor visitor = new AnonymousClassDeclarationVisitor();
    	typeDeclaration.accept(visitor);
    	Set<AnonymousClassDeclaration> anonymousClassDeclarations = visitor.getAnonymousClassDeclarations();
    	
    	DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    	for(AnonymousClassDeclaration anonymous : anonymousClassDeclarations) {
    		insertNode(anonymous, root);
    	}
    	
    	List<UMLAnonymousClass> createdAnonymousClasses = new ArrayList<UMLAnonymousClass>();
    	Enumeration enumeration = root.postorderEnumeration();
    	while(enumeration.hasMoreElements()) {
    		DefaultMutableTreeNode node = (DefaultMutableTreeNode)enumeration.nextElement();
    		if(node.getUserObject() != null) {
    			AnonymousClassDeclaration anonymous = (AnonymousClassDeclaration)node.getUserObject();
    			boolean operationFound = false;
    			boolean attributeFound = false;
    			boolean initializerFound = false;
    			UMLOperation matchingOperation = null;
    			UMLAttribute matchingAttribute = null;
    			UMLInitializer matchingInitializer = null;
    			UMLEnumConstant matchingEnumConstant = null;
    			List<UMLComment> comments = null;
				for(UMLOperation operation : umlClass.getOperations()) {
    				if(operation.getLocationInfo().getStartOffset() <= anonymous.getStartPosition() &&
    						operation.getLocationInfo().getEndOffset() >= anonymous.getStartPosition()+anonymous.getLength()) {
    					comments = operation.getComments();
    					operationFound = true;
    					matchingOperation = operation;
    					break;
    				}
    			}
    			if(!operationFound) {
	    			for(UMLAttribute attribute : umlClass.getAttributes()) {
	    				if(attribute.getLocationInfo().getStartOffset() <= anonymous.getStartPosition() &&
	    						attribute.getLocationInfo().getEndOffset() >= anonymous.getStartPosition()+anonymous.getLength()) {
	    					comments = attribute.getComments();
	    					attributeFound = true;
	    					matchingAttribute = attribute;
	    					break;
	    				}
	    			}
    			}
    			if(!operationFound && !attributeFound) {
    				for(UMLInitializer initializer : umlClass.getInitializers()) {
    					if(initializer.getLocationInfo().getStartOffset() <= anonymous.getStartPosition() &&
    							initializer.getLocationInfo().getEndOffset() >= anonymous.getStartPosition()+anonymous.getLength()) {
	    					comments = initializer.getComments();
	    					initializerFound = true;
	    					matchingInitializer = initializer;
	    					break;
    					}
    				}
    			}
    			if(!operationFound && !attributeFound && !initializerFound) {
    				for(UMLEnumConstant enumConstant : umlClass.getEnumConstants()) {
    					if(enumConstant.getLocationInfo().getStartOffset() <= anonymous.getStartPosition() &&
    							enumConstant.getLocationInfo().getEndOffset() >= anonymous.getStartPosition()+anonymous.getLength()) {
	    					comments = enumConstant.getComments();
	    					matchingEnumConstant = enumConstant;
	    					break;
    					}
    				}
    			}
    			if(matchingOperation != null || matchingAttribute != null || matchingInitializer != null || matchingEnumConstant != null) {
	    			String anonymousBinaryName = getAnonymousBinaryName(node);
	    			String anonymousCodePath = getAnonymousCodePath(node);
	    			UMLAnonymousClass anonymousClass = processAnonymousClassDeclaration(cu, anonymous, packageName + "." + className, anonymousBinaryName, anonymousCodePath, sourceFile, comments, umlClass.getImportedTypes());
	    			umlClass.addAnonymousClass(anonymousClass);
	    			if(matchingOperation != null) {
	    				matchingOperation.addAnonymousClass(anonymousClass);
	    			}
	    			if(matchingAttribute != null) {
	    				matchingAttribute.addAnonymousClass(anonymousClass);
	    			}
	    			if(matchingInitializer != null) {
	    				matchingInitializer.addAnonymousClass(anonymousClass);
	    			}
	    			if(matchingEnumConstant != null) {
	    				matchingEnumConstant.addAnonymousClass(anonymousClass);
	    			}
	    			for(UMLOperation operation : anonymousClass.getOperations()) {
	    				for(UMLAnonymousClass createdAnonymousClass : createdAnonymousClasses) {
	    					if(operation.getLocationInfo().subsumes(createdAnonymousClass.getLocationInfo())) {
	    						operation.addAnonymousClass(createdAnonymousClass);
	    					}
	    				}
	    			}
	    			for(UMLAttribute attribute : anonymousClass.getAttributes()) {
	    				for(UMLAnonymousClass createdAnonymousClass : createdAnonymousClasses) {
	    					if(attribute.getLocationInfo().subsumes(createdAnonymousClass.getLocationInfo())) {
	    						attribute.addAnonymousClass(createdAnonymousClass);
	    					}
	    				}
	    			}
	    			for(UMLInitializer initializer : anonymousClass.getInitializers()) {
	    				for(UMLAnonymousClass createdAnonymousClass : createdAnonymousClasses) {
	    					if(initializer.getLocationInfo().subsumes(createdAnonymousClass.getLocationInfo())) {
	    						initializer.addAnonymousClass(createdAnonymousClass);
	    					}
	    				}
	    			}
	    			for(UMLEnumConstant enumConstant : anonymousClass.getEnumConstants()) {
	    				for(UMLAnonymousClass createdAnonymousClass : createdAnonymousClasses) {
	    					if(enumConstant.getLocationInfo().subsumes(createdAnonymousClass.getLocationInfo())) {
	    						enumConstant.addAnonymousClass(createdAnonymousClass);
	    					}
	    				}
	    			}
	    			createdAnonymousClasses.add(anonymousClass);
	    			List<BodyDeclaration> bodyDeclarations = anonymous.bodyDeclarations();
	    			int i=0;
	    			int j=0;
	    			for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
	    				if(bodyDeclaration instanceof MethodDeclaration) {
	    					MethodDeclaration methodDeclaration = (MethodDeclaration)bodyDeclaration;
	    					UMLOperation operation = anonymousClass.getOperations().get(i);
	    					processMethodBody(cu, sourceFile, methodDeclaration, operation);
	    					i++;
	    				}
	    				else if(bodyDeclaration instanceof Initializer) {
	    					Initializer initializer = (Initializer)bodyDeclaration;
	    					UMLInitializer umlInitializer = anonymousClass.getInitializers().get(j);
	    					processInitializerBody(cu, sourceFile, initializer, umlInitializer);
	    					j++;
	    				}
	    			}
    			}
    		}
    	}
	}

	private void processMethodBody(CompilationUnit cu, String sourceFile, MethodDeclaration methodDeclaration, UMLOperation operation) {
		Block block = methodDeclaration.getBody();
		if(block != null) {
			OperationBody body = new OperationBody(cu, sourceFile, block, operation);
			operation.setBody(body);
			if(block.statements().size() == 0) {
				operation.setEmptyBody(true);
			}
		}
		else {
			operation.setBody(null);
		}
	}

	private void processInitializerBody(CompilationUnit cu, String sourceFile, Initializer initializer, UMLInitializer umlInitializer) {
		Block block = initializer.getBody();
		if(block != null) {
			OperationBody body = new OperationBody(cu, sourceFile, block, umlInitializer);
			umlInitializer.setBody(body);
		}
		else {
			umlInitializer.setBody(null);
		}
	}

	private void processModifiers(CompilationUnit cu, String sourceFile, AbstractTypeDeclaration typeDeclaration, UMLClass umlClass) {
		int modifiers = typeDeclaration.getModifiers();
    	if((modifiers & Modifier.ABSTRACT) != 0)
    		umlClass.setAbstract(true);
    	if((modifiers & Modifier.STATIC) != 0)
    		umlClass.setStatic(true);
    	if((modifiers & Modifier.FINAL) != 0)
    		umlClass.setFinal(true);
    	
    	if((modifiers & Modifier.PUBLIC) != 0)
    		umlClass.setVisibility("public");
    	else if((modifiers & Modifier.PROTECTED) != 0)
    		umlClass.setVisibility("protected");
    	else if((modifiers & Modifier.PRIVATE) != 0)
    		umlClass.setVisibility("private");
    	else
    		umlClass.setVisibility("package");
    	
    	List<IExtendedModifier> extendedModifiers = typeDeclaration.modifiers();
		for(IExtendedModifier extendedModifier : extendedModifiers) {
			if(extendedModifier.isAnnotation()) {
				Annotation annotation = (Annotation)extendedModifier;
				umlClass.addAnnotation(new UMLAnnotation(cu, sourceFile, annotation));
			}
		}
	}

	private UMLInitializer processInitializer(CompilationUnit cu, Initializer initializer, String packageName, boolean isInterfaceMethod, String sourceFile, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, initializer, sourceFile);
		String name = "";
		if(initializer.getParent() instanceof AnonymousClassDeclaration && initializer.getParent().getParent() instanceof ClassInstanceCreation) {
			ClassInstanceCreation creation = (ClassInstanceCreation)initializer.getParent().getParent();
			name = creation.getType().toString();
		}
		else if(initializer.getParent() instanceof AbstractTypeDeclaration) {
			AbstractTypeDeclaration typeDeclaration = (AbstractTypeDeclaration)initializer.getParent();
			name = typeDeclaration.getName().getIdentifier();
		}
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, initializer, CodeElementType.INITIALIZER);
		UMLInitializer umlInitializer = new UMLInitializer(name, locationInfo);
		umlInitializer.setJavadoc(javadoc);
		distributeComments(comments, locationInfo, umlInitializer.getComments());
		
		int methodModifiers = initializer.getModifiers();
		if((methodModifiers & Modifier.STATIC) != 0)
			umlInitializer.setStatic(true);
		
		return umlInitializer;
	}

	private UMLOperation processMethodDeclaration(CompilationUnit cu, MethodDeclaration methodDeclaration, String packageName, boolean isInterfaceMethod, String sourceFile, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, methodDeclaration, sourceFile);
		String methodName = methodDeclaration.getName().getFullyQualifiedName();
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, methodDeclaration, CodeElementType.METHOD_DECLARATION);
		UMLOperation umlOperation = new UMLOperation(methodName, locationInfo);
		umlOperation.setJavadoc(javadoc);
		distributeComments(comments, locationInfo, umlOperation.getComments());
		
		if(methodDeclaration.isConstructor())
			umlOperation.setConstructor(true);
		
		int methodModifiers = methodDeclaration.getModifiers();
		if((methodModifiers & Modifier.PUBLIC) != 0)
			umlOperation.setVisibility("public");
		else if((methodModifiers & Modifier.PROTECTED) != 0)
			umlOperation.setVisibility("protected");
		else if((methodModifiers & Modifier.PRIVATE) != 0)
			umlOperation.setVisibility("private");
		else if(isInterfaceMethod)
			umlOperation.setVisibility("public");
		else
			umlOperation.setVisibility("package");
		
		if((methodModifiers & Modifier.ABSTRACT) != 0)
			umlOperation.setAbstract(true);
		
		if((methodModifiers & Modifier.FINAL) != 0)
			umlOperation.setFinal(true);
		
		if((methodModifiers & Modifier.STATIC) != 0)
			umlOperation.setStatic(true);
		
		if((methodModifiers & Modifier.SYNCHRONIZED) != 0)
			umlOperation.setSynchronized(true);
		
		if((methodModifiers & Modifier.NATIVE) != 0)
			umlOperation.setNative(true);
		
		List<IExtendedModifier> extendedModifiers = methodDeclaration.modifiers();
		for(IExtendedModifier extendedModifier : extendedModifiers) {
			if(extendedModifier.isAnnotation()) {
				Annotation annotation = (Annotation)extendedModifier;
				umlOperation.addAnnotation(new UMLAnnotation(cu, sourceFile, annotation));
			}
		}
		
		List<TypeParameter> typeParameters = methodDeclaration.typeParameters();
		for(TypeParameter typeParameter : typeParameters) {
			UMLTypeParameter umlTypeParameter = new UMLTypeParameter(typeParameter.getName().getFullyQualifiedName(),
					generateLocationInfo(cu, sourceFile, typeParameter, CodeElementType.TYPE_PARAMETER));
			List<Type> typeBounds = typeParameter.typeBounds();
			for(Type type : typeBounds) {
				umlTypeParameter.addTypeBound(UMLType.extractTypeObject(cu, sourceFile, type, 0));
			}
			List<IExtendedModifier> typeParameterExtendedModifiers = typeParameter.modifiers();
			for(IExtendedModifier extendedModifier : typeParameterExtendedModifiers) {
				if(extendedModifier.isAnnotation()) {
					Annotation annotation = (Annotation)extendedModifier;
					umlTypeParameter.addAnnotation(new UMLAnnotation(cu, sourceFile, annotation));
				}
			}
			umlOperation.addTypeParameter(umlTypeParameter);
		}
		
		Type returnType = methodDeclaration.getReturnType2();
		if(returnType != null) {
			UMLType type = UMLType.extractTypeObject(cu, sourceFile, returnType, methodDeclaration.getExtraDimensions());
			UMLParameter returnParameter = new UMLParameter("return", type, "return", false);
			umlOperation.addParameter(returnParameter);
		}
		List<SingleVariableDeclaration> parameters = methodDeclaration.parameters();
		for(SingleVariableDeclaration parameter : parameters) {
			Type parameterType = parameter.getType();
			String parameterName = parameter.getName().getFullyQualifiedName();
			UMLType type = UMLType.extractTypeObject(cu, sourceFile, parameterType, parameter.getExtraDimensions());
			if(parameter.isVarargs()) {
				type.setVarargs();
			}
			UMLParameter umlParameter = new UMLParameter(parameterName, type, "in", parameter.isVarargs());
			VariableDeclaration variableDeclaration = new VariableDeclaration(cu, sourceFile, parameter, umlOperation, parameter.isVarargs());
			variableDeclaration.setParameter(true);
			umlParameter.setVariableDeclaration(variableDeclaration);
			umlOperation.addParameter(umlParameter);
		}
		List<Type> thrownExceptionTypes = methodDeclaration.thrownExceptionTypes();
		for(Type thrownExceptionType : thrownExceptionTypes) {
			UMLType type = UMLType.extractTypeObject(cu, sourceFile, thrownExceptionType, 0);
			umlOperation.addThrownExceptionType(type);
		}
		return umlOperation;
	}

	private void processEnumConstantDeclaration(CompilationUnit cu, EnumConstantDeclaration enumConstantDeclaration, String sourceFile, UMLClass umlClass, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, enumConstantDeclaration, sourceFile);
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, enumConstantDeclaration, CodeElementType.ENUM_CONSTANT_DECLARATION);
		UMLEnumConstant enumConstant = new UMLEnumConstant(enumConstantDeclaration.getName().getIdentifier(), UMLType.extractTypeObject(umlClass.getName()), locationInfo);
		VariableDeclaration variableDeclaration = new VariableDeclaration(cu, sourceFile, enumConstantDeclaration);
		enumConstant.setVariableDeclaration(variableDeclaration);
		enumConstant.setJavadoc(javadoc);
		distributeComments(comments, locationInfo, enumConstant.getComments());
		enumConstant.setFinal(true);
		enumConstant.setStatic(true);
		enumConstant.setVisibility("public");
		List<Expression> arguments = enumConstantDeclaration.arguments();
		for(Expression argument : arguments) {
			enumConstant.addArgument(argument.toString());
		}
		enumConstant.setClassName(umlClass.getName());
		umlClass.addEnumConstant(enumConstant);
	}

	private List<UMLAttribute> processFieldDeclaration(CompilationUnit cu, FieldDeclaration fieldDeclaration, boolean isInterfaceField, String sourceFile, List<UMLComment> comments) {
		UMLJavadoc javadoc = generateJavadoc(cu, fieldDeclaration, sourceFile);
		List<UMLAttribute> attributes = new ArrayList<UMLAttribute>();
		Type fieldType = fieldDeclaration.getType();
		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
		for(VariableDeclarationFragment fragment : fragments) {
			UMLType type = UMLType.extractTypeObject(cu, sourceFile, fieldType, fragment.getExtraDimensions());
			String fieldName = fragment.getName().getFullyQualifiedName();
			LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, fragment, CodeElementType.FIELD_DECLARATION);
			UMLAttribute umlAttribute = new UMLAttribute(fieldName, type, locationInfo);
			VariableDeclaration variableDeclaration = new VariableDeclaration(cu, sourceFile, fragment, umlAttribute);
			variableDeclaration.setAttribute(true);
			umlAttribute.setVariableDeclaration(variableDeclaration);
			umlAttribute.setJavadoc(javadoc);
			distributeComments(comments, locationInfo, umlAttribute.getComments());
			
			int fieldModifiers = fieldDeclaration.getModifiers();
			if((fieldModifiers & Modifier.PUBLIC) != 0)
				umlAttribute.setVisibility("public");
			else if((fieldModifiers & Modifier.PROTECTED) != 0)
				umlAttribute.setVisibility("protected");
			else if((fieldModifiers & Modifier.PRIVATE) != 0)
				umlAttribute.setVisibility("private");
			else if(isInterfaceField)
				umlAttribute.setVisibility("public");
			else
				umlAttribute.setVisibility("package");
			
			if((fieldModifiers & Modifier.FINAL) != 0)
				umlAttribute.setFinal(true);
			
			if((fieldModifiers & Modifier.STATIC) != 0)
				umlAttribute.setStatic(true);
			
			if((fieldModifiers & Modifier.VOLATILE) != 0)
				umlAttribute.setVolatile(true);
			
			if((fieldModifiers & Modifier.TRANSIENT) != 0)
				umlAttribute.setTransient(true);
			
			attributes.add(umlAttribute);
		}
		return attributes;
	}
	
	private UMLAnonymousClass processAnonymousClassDeclaration(CompilationUnit cu, AnonymousClassDeclaration anonymous, String packageName, String binaryName, String codePath, String sourceFile, List<UMLComment> comments, List<String> importedTypes) {
		List<BodyDeclaration> bodyDeclarations = anonymous.bodyDeclarations();
		LocationInfo locationInfo = generateLocationInfo(cu, sourceFile, anonymous, CodeElementType.ANONYMOUS_CLASS_DECLARATION);
		UMLAnonymousClass anonymousClass = new UMLAnonymousClass(packageName, binaryName, codePath, locationInfo, importedTypes);
		
		for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
			if(bodyDeclaration instanceof FieldDeclaration) {
				FieldDeclaration fieldDeclaration = (FieldDeclaration)bodyDeclaration;
				List<UMLAttribute> attributes = processFieldDeclaration(cu, fieldDeclaration, false, sourceFile, comments);
	    		for(UMLAttribute attribute : attributes) {
	    			attribute.setClassName(anonymousClass.getCodePath());
	    			attribute.setDeclaredInAnonymousClass(true);
	    			anonymousClass.addAttribute(attribute);
	    		}
			}
			else if(bodyDeclaration instanceof MethodDeclaration) {
				MethodDeclaration methodDeclaration = (MethodDeclaration)bodyDeclaration;
				UMLOperation operation = processMethodDeclaration(cu, methodDeclaration, packageName, false, sourceFile, comments);
				operation.setClassName(anonymousClass.getCodePath());
				operation.setDeclaredInAnonymousClass(true);
				anonymousClass.addOperation(operation);
			}
			else if(bodyDeclaration instanceof Initializer) {
				Initializer initializer = (Initializer)bodyDeclaration;
				UMLInitializer umlInitializer = processInitializer(cu, initializer, packageName, false, sourceFile, comments);
				umlInitializer.setClassName(anonymousClass.getCodePath());
				umlInitializer.setDeclaredInAnonymousClass(true);
				anonymousClass.addInitializer(umlInitializer);
			}
		}
		distributeComments(comments, locationInfo, anonymousClass.getComments());
		return anonymousClass;
	}
	
	private void insertNode(AnonymousClassDeclaration childAnonymous, DefaultMutableTreeNode root) {
		Enumeration enumeration = root.postorderEnumeration();
		DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childAnonymous);
		
		DefaultMutableTreeNode parentNode = root;
		while(enumeration.hasMoreElements()) {
			DefaultMutableTreeNode currentNode = (DefaultMutableTreeNode)enumeration.nextElement();
			AnonymousClassDeclaration currentAnonymous = (AnonymousClassDeclaration)currentNode.getUserObject();
			if(currentAnonymous != null && isParent(childAnonymous, currentAnonymous)) {
				parentNode = currentNode;
				break;
			}
		}
		parentNode.add(childNode);
	}

	private String getAnonymousCodePath(DefaultMutableTreeNode node) {
		AnonymousClassDeclaration anonymous = (AnonymousClassDeclaration)node.getUserObject();
		String name = "";
		ASTNode parent = anonymous.getParent();
		while(parent != null) {
			if(parent instanceof MethodDeclaration) {
				String methodName = ((MethodDeclaration)parent).getName().getIdentifier();
				if(name.isEmpty()) {
					name = methodName;
				}
				else {
					name = methodName + "." + name;
				}
			}
			else if(parent instanceof VariableDeclarationFragment &&
					(parent.getParent() instanceof FieldDeclaration ||
					parent.getParent() instanceof VariableDeclarationStatement)) {
				String fieldName = ((VariableDeclarationFragment)parent).getName().getIdentifier();
				if(name.isEmpty()) {
					name = fieldName;
				}
				else {
					name = fieldName + "." + name;
				}
			}
			else if(parent instanceof MethodInvocation) {
				String invocationName = ((MethodInvocation)parent).getName().getIdentifier();
				if(name.isEmpty()) {
					name = invocationName;
				}
				else {
					name = invocationName + "." + name;
				}
			}
			else if(parent instanceof SuperMethodInvocation) {
				String invocationName = ((SuperMethodInvocation)parent).getName().getIdentifier();
				if(name.isEmpty()) {
					name = invocationName;
				}
				else {
					name = invocationName + "." + name;
				}
			}
			else if(parent instanceof ClassInstanceCreation) {
				String invocationName = ((ClassInstanceCreation)parent).getType().toString();
				if(name.isEmpty()) {
					name = "new " + invocationName;
				}
				else {
					name = "new " + invocationName + "." + name;
				}
			}
			parent = parent.getParent();
		}
		return name.toString();
	}

	private String getAnonymousBinaryName(DefaultMutableTreeNode node) {
		StringBuilder name = new StringBuilder();
		TreeNode[] path = node.getPath();
		for(int i=0; i<path.length; i++) {
			DefaultMutableTreeNode tmp = (DefaultMutableTreeNode)path[i];
			if(tmp.getUserObject() != null) {
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)tmp.getParent();
				int index = parent.getIndex(tmp);
				name.append(index+1);
				if(i < path.length-1)
					name.append(".");
			}
		}
		return name.toString();
	}
	
	private boolean isParent(ASTNode child, ASTNode parent) {
		ASTNode current = child;
		while(current.getParent() != null) {
			if(current.getParent().equals(parent))
				return true;
			current = current.getParent();
		}
		return false;
	}

	private LocationInfo generateLocationInfo(CompilationUnit cu, String sourceFile, ASTNode node, CodeElementType codeElementType) {
		return new LocationInfo(cu, sourceFile, node, codeElementType);
	}
}
