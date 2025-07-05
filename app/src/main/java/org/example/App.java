package org.example;

import java.io.File;
import java.io.FileWriter;

public class App {
    
    private static class Options {
        String inputFile;
        String outputFile = "output.xml";
        String format = "XML"; // XML or JSON
        boolean verbose = false;
        
        // CQL Compiler Options - you can modify these defaults
        boolean dateRangeOptimization = false;
        boolean annotations = true;  // Set to true as requested
        boolean locators = true;     // Set to true as requested
        boolean resultTypes = false;
        String signatures = "None"; // None|Differing|Overloads|All
        boolean detailedErrors = false;
        boolean disableListTraversal = false;
        boolean disableListDemotion = false;
        boolean disableListPromotion = false;
        boolean enableIntervalDemotion = false;
        boolean enableIntervalPromotion = false;
        boolean disableMethodInvocation = false;
        boolean requireFromKeyword = false;
        boolean strict = false;
        boolean debug = false;
        boolean validateUnits = false;
    }
    
    public static void main(String[] args) throws Exception {
        Options options = parseArgs(args);
        
        if (options.inputFile == null) {
            printHelp();
            return;
        }
        
        File input = new File(options.inputFile);
        if (!input.exists()) {
            System.err.println("CQL file not found: " + input.getAbsolutePath());
            System.exit(1);
        }
        
        // Auto-detect library path
        String libraryPath = input.getParent();
        
        if (options.verbose) {
            System.out.println("Input file: " + input.getAbsolutePath());
            System.out.println("Output file: " + options.outputFile);
            System.out.println("Output format: " + options.format);
            System.out.println("Library path: " + libraryPath);
            System.out.println("Annotations: " + options.annotations);
            System.out.println("Locators: " + options.locators);
            System.out.println("Detailed errors: " + options.detailedErrors);
        }
        
        System.out.println("Attempting to translate CQL to ELM...");
        
        try {
            // Create ModelManager with default configuration
            Class<?> modelManagerClass = Class.forName("org.cqframework.cql.cql2elm.ModelManager");
            Object modelManager = modelManagerClass.getDeclaredConstructor().newInstance();
            
            // Create LibraryManager 
            Class<?> libraryManagerClass = Class.forName("org.cqframework.cql.cql2elm.LibraryManager");
            Object libraryManager = libraryManagerClass.getDeclaredConstructor(modelManagerClass).newInstance(modelManager);
            
            // Set up library path for dependent libraries
            setupLibraryPath(libraryManager, libraryManagerClass, libraryPath, options.verbose);
            
            // Create CqlTranslator with compiler options
            Object translator = createTranslatorWithOptions(input, libraryManager, libraryManagerClass, options);
            
            if (translator == null) {
                System.err.println("Could not create CqlTranslator");
                return;
            }
            
            // Check for errors
            Class<?> translatorClass = translator.getClass();
            var getErrorsMethod = translatorClass.getMethod("getErrors");
            java.util.List<?> errors = (java.util.List<?>) getErrorsMethod.invoke(translator);
            
            if (errors.size() > 0) {
                System.err.println("Translation failed due to " + errors.size() + " errors:");
                int errorCount = 0;
                for (Object error : errors) {
                    errorCount++;
                    System.err.println("  " + errorCount + ". " + error.toString());
                    
                    // Only show first 10 errors to avoid overwhelming output
                    if (errorCount >= 10) {
                        System.err.println("  ... and " + (errors.size() - 10) + " more errors");
                        break;
                    }
                }
                return;
            }
            
            // Get output in the requested format
            String output;
            if ("JSON".equalsIgnoreCase(options.format)) {
                var toJsonMethod = translatorClass.getMethod("toJson");
                output = (String) toJsonMethod.invoke(translator);
                
                // Update output file extension if needed
                if (options.outputFile.endsWith(".xml")) {
                    options.outputFile = options.outputFile.replace(".xml", ".json");
                }
            } else {
                var toXmlMethod = translatorClass.getMethod("toXml");
                output = (String) toXmlMethod.invoke(translator);
            }
            
            // Write to output file
            File outputFile = new File(options.outputFile);
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(output);
            }
            
            System.out.println("Translation successful!");
            System.out.println("Input: " + input.getAbsolutePath());
            System.out.println("Output: " + outputFile.getAbsolutePath());
            System.out.println("Format: " + options.format);
            System.out.println("Generated " + output.length() + " characters of ELM " + options.format.toLowerCase());
            
        } catch (Exception e) {
            System.err.println("Error during translation: " + e.getMessage());
            if (options.verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Use --verbose for more details");
            }
        }
    }
    
    private static Object createTranslatorWithOptions(File input, Object libraryManager, 
                                                    Class<?> libraryManagerClass, Options options) throws Exception {
        
        Class<?> translatorClass = Class.forName("org.cqframework.cql.cql2elm.CqlTranslator");
        
        // Try to create translator with CqlTranslatorOptions if available
        try {
            Class<?> optionsClass = Class.forName("org.cqframework.cql.cql2elm.CqlTranslatorOptions");
            Object translatorOptions = optionsClass.getDeclaredConstructor().newInstance();
            
            // Try to set options using reflection
            setCqlTranslatorOptions(translatorOptions, optionsClass, options);
            
            // Look for fromFile method that takes options
            var methods = translatorClass.getMethods();
            for (var method : methods) {
                if ("fromFile".equals(method.getName())) {
                    var paramTypes = method.getParameterTypes();
                    if (paramTypes.length == 3 && 
                        paramTypes[0] == File.class &&
                        paramTypes[1] == libraryManagerClass &&
                        paramTypes[2] == optionsClass) {
                        
                        if (options.verbose) {
                            System.out.println("Using CqlTranslator.fromFile with options");
                        }
                        
                        return method.invoke(null, input, libraryManager, translatorOptions);
                    }
                }
            }
        } catch (Exception e) {
            if (options.verbose) {
                System.out.println("CqlTranslatorOptions approach failed, trying basic approach: " + e.getMessage());
            }
        }
        
        // Fall back to basic fromFile method
        try {
            var fromFileMethod = translatorClass.getMethod("fromFile", File.class, libraryManagerClass);
            if (options.verbose) {
                System.out.println("Using basic CqlTranslator.fromFile");
            }
            return fromFileMethod.invoke(null, input, libraryManager);
        } catch (Exception e) {
            if (options.verbose) {
                System.out.println("Basic fromFile failed: " + e.getMessage());
            }
        }
        
        return null;
    }
    
    private static void setCqlTranslatorOptions(Object translatorOptions, Class<?> optionsClass, Options options) {
        try {
            // Try to set various options using reflection
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableDateRangeOptimization", options.dateRangeOptimization);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableAnnotations", options.annotations);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableLocators", options.locators);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableResultTypes", options.resultTypes);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableDetailedErrors", options.detailedErrors);
            setOptionIfAvailable(translatorOptions, optionsClass, "setDisableListTraversal", options.disableListTraversal);
            setOptionIfAvailable(translatorOptions, optionsClass, "setDisableListDemotion", options.disableListDemotion);
            setOptionIfAvailable(translatorOptions, optionsClass, "setDisableListPromotion", options.disableListPromotion);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableIntervalDemotion", options.enableIntervalDemotion);
            setOptionIfAvailable(translatorOptions, optionsClass, "setEnableIntervalPromotion", options.enableIntervalPromotion);
            setOptionIfAvailable(translatorOptions, optionsClass, "setDisableMethodInvocation", options.disableMethodInvocation);
            setOptionIfAvailable(translatorOptions, optionsClass, "setRequireFromKeyword", options.requireFromKeyword);
            setOptionIfAvailable(translatorOptions, optionsClass, "setStrict", options.strict);
            setOptionIfAvailable(translatorOptions, optionsClass, "setDebug", options.debug);
            setOptionIfAvailable(translatorOptions, optionsClass, "setValidateUnits", options.validateUnits);
            
            // Handle signatures enum if available
            setSignatureOptionIfAvailable(translatorOptions, optionsClass, options.signatures);
            
        } catch (Exception e) {
            // Silently continue if options can't be set
        }
    }
    
    private static void setOptionIfAvailable(Object translatorOptions, Class<?> optionsClass, String methodName, boolean value) {
        try {
            var method = optionsClass.getMethod(methodName, boolean.class);
            method.invoke(translatorOptions, value);
        } catch (Exception e) {
            // Method doesn't exist, skip it
        }
    }
    
    private static void setSignatureOptionIfAvailable(Object translatorOptions, Class<?> optionsClass, String signatureValue) {
        try {
            // Try to find and set signature level
            var method = optionsClass.getMethod("setSignatureLevel", String.class);
            method.invoke(translatorOptions, signatureValue);
        } catch (Exception e) {
            // Try enum approach
            try {
                Class<?> signatureEnum = Class.forName("org.cqframework.cql.cql2elm.LibraryBuilder$SignatureLevel");
                Object enumValue = Enum.valueOf((Class<Enum>) signatureEnum, signatureValue.toUpperCase());
                var method = optionsClass.getMethod("setSignatureLevel", signatureEnum);
                method.invoke(translatorOptions, enumValue);
            } catch (Exception e2) {
                // Skip if can't set signature level
            }
        }
    }
    
    private static void setupLibraryPath(Object libraryManager, Class<?> libraryManagerClass, 
                                       String libraryPath, boolean verbose) {
        try {
            if (verbose) {
                System.out.println("Setting up library path: " + libraryPath);
            }
            
            var getLibrarySourceLoaderMethod = libraryManagerClass.getMethod("getLibrarySourceLoader");
            Object librarySourceLoader = getLibrarySourceLoaderMethod.invoke(libraryManager);
            
            Class<?> loaderClass = librarySourceLoader.getClass();
            Class<?> providerInterface = Class.forName("org.cqframework.cql.cql2elm.LibrarySourceProvider");
            Object directoryProvider = createDirectoryProvider(libraryPath, providerInterface, verbose);
            
            var registerProviderMethod = loaderClass.getMethod("registerProvider", providerInterface);
            registerProviderMethod.invoke(librarySourceLoader, directoryProvider);
            
            if (verbose) {
                System.out.println("Successfully registered directory provider");
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
    
    private static Options parseArgs(String[] args) {
        Options options = new Options();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            switch (arg) {
                case "--help":
                case "-h":
                    return options; // Will trigger help
                    
                case "--input":
                case "-i":
                    if (i + 1 < args.length) {
                        options.inputFile = args[++i];
                    }
                    break;
                    
                case "--output":
                case "-o":
                    if (i + 1 < args.length) {
                        options.outputFile = args[++i];
                    }
                    break;
                    
                case "--format":
                case "-f":
                    if (i + 1 < args.length) {
                        options.format = args[++i].toUpperCase();
                    }
                    break;
                    
                case "--verbose":
                case "-v":
                    options.verbose = true;
                    break;
                    
                case "--annotations":
                    if (i + 1 < args.length) {
                        options.annotations = Boolean.parseBoolean(args[++i]);
                    } else {
                        options.annotations = true;
                    }
                    break;
                    
                case "--locators":
                    if (i + 1 < args.length) {
                        options.locators = Boolean.parseBoolean(args[++i]);
                    } else {
                        options.locators = true;
                    }
                    break;
                    
                case "--detailed-errors":
                    if (i + 1 < args.length) {
                        options.detailedErrors = Boolean.parseBoolean(args[++i]);
                    } else {
                        options.detailedErrors = true;
                    }
                    break;
                    
                default:
                    System.err.println("Unknown option: " + arg);
                    break;
            }
        }
        
        return options;
    }
    
    private static void printHelp() {
        System.out.println("CQL-to-ELM Translator");
        System.out.println();
        System.out.println("Usage: java -jar cql-cli.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --input, -i <file>         Input CQL file (REQUIRED)");
        System.out.println("  --output, -o <file>        Output ELM file (default: output.xml)");
        System.out.println("  --format, -f <format>      Output format: XML or JSON (default: XML)");
        System.out.println("  --annotations [true|false] Include source annotations (default: true)");
        System.out.println("  --locators [true|false]    Include source locators (default: true)");
        System.out.println("  --detailed-errors [true|false] Enable detailed errors (default: false)");
        System.out.println("  --verbose, -v              Verbose output");
        System.out.println("  --help, -h                 Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Basic XML output");
        System.out.println("  java -jar cql-cli.jar --input MainLibrary.cql");
        System.out.println();
        System.out.println("  # JSON output with annotations and locators");
        System.out.println("  java -jar cql-cli.jar --input main.cql --format JSON --output main.json");
        System.out.println();
        System.out.println("  # Verbose mode with detailed errors");
        System.out.println("  java -jar cql-cli.jar --input test.cql --verbose --detailed-errors");
    }
}