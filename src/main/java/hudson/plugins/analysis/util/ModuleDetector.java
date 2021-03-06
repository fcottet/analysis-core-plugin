package hudson.plugins.analysis.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.digester.Digester;
import org.apache.commons.lang.StringUtils;
import org.xml.sax.SAXException;

/**
 * Detects module names by parsing the name of a source file, the Maven pom.xml file or the ANT build.xml file.
 *
 * @author Ulli Hafner
 */
public class ModuleDetector {
    private static final String BACK_SLASH = "\\";
    private static final String SLASH = "/";
    private static final String ALL_DIRECTORIES = "**/";

    /** Filename of Maven pom. */
    protected static final String MAVEN_POM = "pom.xml";
    /** Filename of Ant project file. */
    protected static final String ANT_PROJECT = "build.xml";
    /** Prefix of a Maven target folder. */
    private static final String TARGET = "/target";
    /** The factory to create input streams with. */
    private FileInputStreamFactory factory = new DefaultFileInputStreamFactory();
    /** Maps file names to module names. */
    private final Map<String, String> fileNameToModuleName;
    /** Sorted list of file name prefixes. */
    private final List<String> prefixes;

    /**
     * Creates a new instance of {@link ModuleDetector}.
     */
    public ModuleDetector() {
        fileNameToModuleName = new HashMap<String, String>();
        prefixes = new ArrayList<String>();
    }

    /**
     * Creates a new instance of {@link ModuleDetector}.
     *
     * @param workspace
     *            the workspace to scan for maven pom.xml or ant build.xml files
     */
    public ModuleDetector(final File workspace) {
        this(workspace, new DefaultFileInputStreamFactory());
    }

    /**
     * Creates a new instance of {@link ModuleDetector}.
     *
     * @param workspace
     *            the workspace to scan for Maven pom.xml or ant build.xml files
     * @param fileInputStreamFactory the value to set
     */
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    public ModuleDetector(final File workspace, final FileInputStreamFactory fileInputStreamFactory) {
        setFileInputStreamFactory(fileInputStreamFactory);
        fileNameToModuleName = createFilesToModuleMapping(workspace);
        prefixes = new ArrayList<String>(fileNameToModuleName.keySet());
        Collections.sort(prefixes);
    }

    /**
     * Sets the factory to the specified value.
     *
     * @param fileInputStreamFactory the value to set
     */
    public final void setFileInputStreamFactory(final FileInputStreamFactory fileInputStreamFactory) {
        factory = fileInputStreamFactory;
    }

    /**
     * Returns a mapping of path prefixes to module names.
     *
     * @param workspace
     *            the workspace to start scanning for files
     * @return the mapping of path prefixes to module names
     */
    private Map<String, String> createFilesToModuleMapping(final File workspace) {
        String[] projects = findMavenModules(workspace);
        Map<String, String> mapping = new HashMap<String, String>();
        if (projects.length > 0) {
            for (int i = 0; i < projects.length; i++) {
                String fileName = projects[i];
                String moduleName = parsePom(fileName);
                if (StringUtils.isNotBlank(moduleName)) {
                    mapping.put(StringUtils.substringBeforeLast(fileName, MAVEN_POM), moduleName);
                }
            }
        }
        if (mapping.isEmpty()) {
            projects = findAntProjects(workspace);
            for (int i = 0; i < projects.length; i++) {
                String fileName = projects[i];
                String moduleName = parseBuildXml(fileName);
                if (StringUtils.isNotBlank(moduleName)) {
                    mapping.put(StringUtils.substringBeforeLast(fileName, ANT_PROJECT), moduleName);
                }
            }
        }

        return mapping;
    }

    /**
     * Uses the path prefixes of pom.xml or build.xml files to guess a module
     * name for the specified file.
     *
     * @param originalFileName
     *            file name to guess a module for
     * @return a module name or an empty string
     */
    public String guessModuleName(final String originalFileName) {
        String fullPath = originalFileName.replace('\\', '/');

        String guessedModule = StringUtils.EMPTY;
        for (String path : prefixes) {
            if (fullPath.startsWith(path)) {
                guessedModule = fileNameToModuleName.get(path);
            }
        }
        return guessedModule;
    }

    /**
     * Returns the maven modules in the workspace.
     *
     * @param workspace the workspace
     * @return the maven modules in the workspace
     */
    private String[] findMavenModules(final File workspace) {
        return find(workspace, ALL_DIRECTORIES + MAVEN_POM);
    }

    /**
     * Returns the Ant projects in the workspace.
     *
     * @param workspace the workspace
     * @return the Ant projects in the workspace
     */
    private String[] findAntProjects(final File workspace) {
        return find(workspace, ALL_DIRECTORIES + ANT_PROJECT);
    }

    /**
     * Finds files of the matching pattern.
     *
     * @param path
     *            root path to scan in
     * @param pattern
     *            pattern of files
     * @return the found files
     */
    protected String[] find(final File path, final String pattern) {
        String[] relativeFileNames = new FileFinder(pattern).find(path);
        String[] absoluteFileNames = new String[relativeFileNames.length];

        String absolutePath = path.getAbsolutePath();
        for (int file = 0; file < absoluteFileNames.length; file++) {
            absoluteFileNames[file] = (absolutePath + SLASH + relativeFileNames[file]).replace(BACK_SLASH, SLASH);
        }
        return absoluteFileNames;
    }

    /**
     * Guesses a module name based on the source folder or the content in the pom.xml or build.xml files.
     *
     * @param fileName
     *            the absolute path of the file (UNIX style) to guess the module
     *            for
     * @param isMavenBuild
     *            determines whether this build uses maven
     * @param isAntBuild
     *            determines whether this build uses maven
     * @return the guessed module name or an empty string if the name could not be
     *         resolved
     */
    public String guessModuleName(final String fileName, final boolean isMavenBuild, final boolean isAntBuild) {
        String unixName = fileName.replace(BACK_SLASH, SLASH);

        if (isMavenBuild) {
            String projectName = parsePom(unixName);
            if (StringUtils.isNotBlank(projectName)) {
                return projectName;
            }
        }
        String path = StringUtils.substringBeforeLast(unixName, SLASH);

        if (isAntBuild) {
            String projectName = parseBuildXml(path);
            if (StringUtils.isNotBlank(projectName)) {
                return projectName;
            }
        }

        if (path.contains(SLASH)) {
            return StringUtils.substringAfterLast(path, SLASH);
        }
        else {
            return path;
        }
    }

    /**
     * Returns the project name stored in the build.xml.
     *
     * @param path
     *            root folder
     * @return the project name or an empty string if the name could not be
     *         resolved
     */
    private String parseBuildXml(final String path) {
        try {
            String fileName;
            if (StringUtils.isBlank(path)) {
                fileName = ANT_PROJECT;
            }
            else {
                fileName = path + "/build.xml";
            }
            InputStream pom = factory.create(fileName);

            Digester digester = new Digester();
            digester.setValidating(false);
            digester.setClassLoader(ModuleDetector.class.getClassLoader());

            digester.push(new StringBuffer());
            String xPath = "project";
            digester.addCallMethod(xPath, "append", 1);
            digester.addCallParam(xPath, 0, "name");

            StringBuffer result = (StringBuffer)digester.parse(pom);
            return result.toString();
        }
        catch (IOException exception) {
            // ignore
        }
        catch (SAXException exception) {
            // ignore
        }
        return StringUtils.EMPTY;
    }

    /**
     * Returns the project name stored in the POM.
     *
     * @param fileName
     *            maven module root folder
     * @return the project name or an empty string if the name could not be
     *         resolved
     */
    private String parsePom(final String fileName) {
        try {
            InputStream pom = null;
            if (fileName.endsWith(MAVEN_POM)) {
                pom = factory.create(fileName);
            }
            else if (fileName.contains(TARGET)) {
                String module = StringUtils.substringBeforeLast(fileName, TARGET);
                pom = factory.create(module + "/pom.xml");
            }
            if (pom != null) {
                Digester digester = new Digester();
                digester.setValidating(false);
                digester.setClassLoader(ModuleDetector.class.getClassLoader());

                digester.push(new StringBuffer());
                digester.addCallMethod("project/name", "append", 0);

                StringBuffer result = (StringBuffer)digester.parse(pom);
                return result.toString();
            }
        }
        catch (IOException exception) {
            // ignore
        }
        catch (SAXException exception) {
            // ignore
        }
        return StringUtils.EMPTY;
    }

    /**
     * A input stream factory based on a {@link FileInputStream}.
     */
    private static final class DefaultFileInputStreamFactory implements FileInputStreamFactory {
        /** {@inheritDoc} */
        public InputStream create(final String fileName) throws FileNotFoundException {
            return new FileInputStream(new File(fileName));
        }
    }
}

