package org.example;

import java.io.File;
import java.io.FileWriter;

public class App {
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("--input")) {
            System.err.println("Usage: java -jar cql-cli.jar --input <path-to-cql-file> [--verbose]");
            System.exit(1);
        }
        
        String inputPath = args[1];
        boolean verbose = args.length > 2 && "--verbose".equals(args[2]);
        
        File input = new File(inputPath);
        if (!input.exists()) {
            System.err.println("CQL file not found: " + input.getAbsolutePath());
            System.exit(1);
        }
        
        // Auto-detect library path
        String libraryPath = input.getParent();
        
        if (verbose) {
            System.out.println("Input file: " + input.getAbsolutePath());
            System.out.println("Library path: " + libraryPath);
            System.out.println("Trying built-in model resolution approach...");
        }
        
        System.out.println("Attempting to translate CQL to ELM...");
        
        try {
            // Try a completely different approach - let the CQL library handle everything
            // Create a default ModelManager (no custom model loading)
            Class<?> modelManagerClass = Class.forName("org.cqframework.cql.cql2elm.ModelManager");
            Object modelManager = modelManagerClass.getDeclaredConstructor().newInstance();
            
            if (verbose) {
                System.out.println("Created default ModelManager");
                
                // Check what the default ModelManager has available
                try {
                    // Look for any methods that might tell us about available models
                    var methods = modelManagerClass.getMethods();
                    for (var method : methods) {
                        String name = method.getName();
                        if ((name.contains("resolve") || name.contains("Model") || name.contains("Provider")) && 
                            method.getParameterCount() <= 2) {
                            System.out.println("Available method: " + method.getName() + 
                                "(" + java.util.Arrays.toString(method.getParameterTypes()) + ")");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Could not inspect ModelManager methods");
                }
            }
            
            // Create LibraryManager 
            Class<?> libraryManagerClass = Class.forName("org.cqframework.cql.cql2elm.LibraryManager");
            Object libraryManager = libraryManagerClass.getDeclaredConstructor(modelManagerClass).newInstance(modelManager);
            
            // Set up library path for dependent libraries
            setupLibraryPath(libraryManager, libraryManagerClass, libraryPath, verbose);
            
            // Try different CqlTranslator creation approaches
            Class<?> translatorClass = Class.forName("org.cqframework.cql.cql2elm.CqlTranslator");
            Object translator = null;
            
            // Approach 1: Basic fromFile with LibraryManager
            if (verbose) {
                System.out.println("Trying CqlTranslator.fromFile(File, LibraryManager)...");
            }
            
            try {
                var fromFileMethod = translatorClass.getMethod("fromFile", File.class, libraryManagerClass);
                translator = fromFileMethod.invoke(null, input, libraryManager);
                if (verbose) {
                    System.out.println("Successfully created translator with fromFile(File, LibraryManager)");
                }
            } catch (Exception e) {
                if (verbose) {
                    System.out.println("fromFile(File, LibraryManager) failed: " + e.getMessage());
                }
            }
            
            // Approach 2: Try with CqlCompilerOptions if available
            if (translator == null) {
                try {
                    if (verbose) {
                        System.out.println("Trying to find CqlCompilerOptions...");
                    }
                    
                    Class<?> optionsClass = Class.forName("org.cqframework.cql.cql2elm.CqlCompilerOptions");
                    Object options = optionsClass.getDeclaredConstructor().newInstance();
                    
                    // Look for a fromFile method that takes options
                    var methods = translatorClass.getMethods();
                    for (var method : methods) {
                        if ("fromFile".equals(method.getName())) {
                            var paramTypes = method.getParameterTypes();
                            if (paramTypes.length == 3 && 
                                paramTypes[0] == File.class &&
                                paramTypes[1] == libraryManagerClass &&
                                paramTypes[2] == optionsClass) {
                                
                                translator = method.invoke(null, input, libraryManager, options);
                                if (verbose) {
                                    System.out.println("Successfully created translator with fromFile(File, LibraryManager, Options)");
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.out.println("CqlCompilerOptions approach failed: " + e.getMessage());
                    }
                }
            }
            
            // Approach 3: Try fromText instead of fromFile
            if (translator == null) {
                try {
                    if (verbose) {
                        System.out.println("Trying CqlTranslator.fromText...");
                    }
                    
                    String cqlContent = new String(java.nio.file.Files.readAllBytes(input.toPath()));
                    var fromTextMethod = translatorClass.getMethod("fromText", String.class, libraryManagerClass);
                    translator = fromTextMethod.invoke(null, cqlContent, libraryManager);
                    
                    if (verbose) {
                        System.out.println("Successfully created translator with fromText(String, LibraryManager)");
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.out.println("fromText approach failed: " + e.getMessage());
                    }
                }
            }
            
            if (translator == null) {
                System.err.println("Could not create CqlTranslator with any approach");
                return;
            }
            
            // Check for errors
            var getErrorsMethod = translatorClass.getMethod("getErrors");
            java.util.List<?> errors = (java.util.List<?>) getErrorsMethod.invoke(translator);
            
            if (errors.size() > 0) {
                System.err.println("Translation failed due to errors:");
                int errorCount = 0;
                
                // Group similar errors to reduce noise
                java.util.Map<String, Integer> errorCounts = new java.util.HashMap<>();
                for (Object error : errors) {
                    String errorMsg = error.toString();
                    // Simplify error message for grouping
                    if (errorMsg.contains("Could not resolve model info provider")) {
                        errorMsg = "Could not resolve model info provider";
                    } else if (errorMsg.contains("Could not resolve model with namespace")) {
                        errorMsg = "Could not resolve model with namespace";
                    } else if (errorMsg.contains("Cannot invoke")) {
                        errorMsg = "Type resolution error";
                    } else if (errorMsg.contains("Could not resolve type name")) {
                        errorMsg = "Could not resolve type name";
                    }
                    errorCounts.put(errorMsg, errorCounts.getOrDefault(errorMsg, 0) + 1);
                }
                
                for (var entry : errorCounts.entrySet()) {
                    System.err.println("  " + entry.getKey() + " (" + entry.getValue() + " occurrences)");
                }
                
                if (verbose) {
                    System.err.println("\nDetailed analysis:");
                    System.err.println("- Total errors: " + errors.size());
                    System.err.println("- Input file exists: " + input.exists());
                    System.err.println("- Input file size: " + input.length() + " bytes");
                    
                    // List CQL files found in the directory
                    File libDir = new File(libraryPath);
                    if (libDir.exists() && libDir.isDirectory()) {
                        File[] cqlFiles = libDir.listFiles((dir, name) -> name.endsWith(".cql"));
                        if (cqlFiles != null && cqlFiles.length > 0) {
                            System.err.println("- CQL files found in library path:");
                            for (File cqlFile : cqlFiles) {
                                System.err.println("  * " + cqlFile.getName());
                            }
                        }
                    }
                    
                    // Check what CQL content looks like
                    try {
                        String content = new String(java.nio.file.Files.readAllBytes(input.toPath()));
                        System.err.println("- CQL library name: " + extractLibraryName(content));
                        System.err.println("- CQL using statements: " + extractUsingStatements(content));
                        System.err.println("- CQL include statements: " + extractIncludeStatements(content));
                    } catch (Exception e) {
                        System.err.println("- Could not read CQL content: " + e.getMessage());
                    }
                    
                    System.err.println("\nThe main issue appears to be that QICore model info is not available");
                    System.err.println("in the built-in model providers for version 3.26.0 of the CQL library.");
                }
                return;
            }
            
            // Get XML output
            var toXmlMethod = translatorClass.getMethod("toXml");
            String output = (String) toXmlMethod.invoke(translator);
            
            // Write to output file
            File outputFile = new File("output.xml");
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(output);
            }
            
            System.out.println("Translation successful!");
            System.out.println("Input: " + input.getAbsolutePath());
            System.out.println("Output: " + outputFile.getAbsolutePath());
            System.out.println("Generated " + output.length() + " characters of ELM XML");
            
        } catch (Exception e) {
            System.err.println("Error during translation: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Use --verbose for more details");
            }
        }
    }
    
    private static void setupLibraryPath(Object libraryManager, Class<?> libraryManagerClass, 
                                       String libraryPath, boolean verbose) {
        try {
            if (verbose) {
                System.out.println("Setting up library path: " + libraryPath);
            }
            
            // Try to work with the LibrarySourceLoader
            try {
                var getLibrarySourceLoaderMethod = libraryManagerClass.getMethod("getLibrarySourceLoader");
                Object librarySourceLoader = getLibrarySourceLoaderMethod.invoke(libraryManager);
                
                if (verbose) {
                    System.out.println("Got LibrarySourceLoader: " + librarySourceLoader.getClass().getName());
                }
                
                // Try to register a directory-based provider with the loader
                Class<?> loaderClass = librarySourceLoader.getClass();
                
                try {
                    Class<?> providerInterface = Class.forName("org.cqframework.cql.cql2elm.LibrarySourceProvider");
                    Object directoryProvider = createDirectoryProvider(libraryPath, providerInterface, verbose);
                    
                    var registerProviderMethod = loaderClass.getMethod("registerProvider", providerInterface);
                    registerProviderMethod.invoke(librarySourceLoader, directoryProvider);
                    
                    if (verbose) {
                        System.out.println("Successfully registered directory provider");
                    }
                    return;
                    
                } catch (Exception e) {
                    if (verbose) {
                        System.out.println("Directory provider registration failed: " + e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                if (verbose) {
                    System.out.println("Could not work with LibrarySourceLoader: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            if (verbose) {
                System.err.println("Warning: Could not set up library path: " + e.getMessage());
            }
        }
    }
    
    private static Object createDirectoryProvider(String libraryPath, Class<?> providerInterface, boolean verbose) {
        try {
            return java.lang.reflect.Proxy.newProxyInstance(
                providerInterface.getClassLoader(),
                new Class<?>[] { providerInterface },
                (proxy, method, args) -> {
                    if (("getLibrarySource".equals(method.getName()) || "getLibraryContent".equals(method.getName())) 
                        && args != null && args.length >= 1) {
                        
                        Object libraryIdentifier = args[0];
                        String libraryName = extractLibraryName(libraryIdentifier);
                        
                        if (libraryName != null) {
                            java.io.File libFile = new java.io.File(libraryPath, libraryName + ".cql");
                            if (libFile.exists()) {
                                // Don't log every single library load in this version to reduce noise
                                try {
                                    String content = new String(java.nio.file.Files.readAllBytes(libFile.toPath()));
                                    
                                    if (args.length >= 2) {
                                        String format = args[1].toString();
                                        if ("CQL".equals(format)) {
                                            return new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                        } else {
                                            return null; // Trigger CQL compilation
                                        }
                                    } else {
                                        return new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    }
                                } catch (Exception e) {
                                    // Silent failure for individual files
                                }
                            }
                        }
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            if (verbose) {
                System.out.println("Could not create directory provider: " + e.getMessage());
            }
            return null;
        }
    }
    
    private static String extractLibraryName(Object libraryIdentifier) {
        try {
            if (libraryIdentifier == null) return null;
            
            if (libraryIdentifier instanceof String) {
                return (String) libraryIdentifier;
            }
            
            try {
                var getIdMethod = libraryIdentifier.getClass().getMethod("getId");
                Object id = getIdMethod.invoke(libraryIdentifier);
                if (id instanceof String) {
                    return (String) id;
                }
            } catch (Exception e) {
                // Try getName
                try {
                    var getNameMethod = libraryIdentifier.getClass().getMethod("getName");
                    Object name = getNameMethod.invoke(libraryIdentifier);
                    if (name instanceof String) {
                        return (String) name;
                    }
                } catch (Exception e2) {
                    // Ignore
                }
            }
            
            return libraryIdentifier.toString();
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String extractLibraryName(String cqlContent) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("library\\s+(\\w+)");
            java.util.regex.Matcher matcher = pattern.matcher(cqlContent);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
    
    private static String extractUsingStatements(String cqlContent) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("using\\s+(\\w+)\\s+version\\s+'([^']+)'");
            java.util.regex.Matcher matcher = pattern.matcher(cqlContent);
            StringBuilder using = new StringBuilder();
            while (matcher.find()) {
                if (using.length() > 0) using.append(", ");
                using.append(matcher.group(1)).append(" ").append(matcher.group(2));
            }
            return using.toString();
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
    
    private static String extractIncludeStatements(String cqlContent) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("include\\s+(\\w+)\\s+version\\s+'([^']+)'");
            java.util.regex.Matcher matcher = pattern.matcher(cqlContent);
            StringBuilder includes = new StringBuilder();
            while (matcher.find()) {
                if (includes.length() > 0) includes.append(", ");
                includes.append(matcher.group(1)).append(" ").append(matcher.group(2));
            }
            return includes.toString();
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
}