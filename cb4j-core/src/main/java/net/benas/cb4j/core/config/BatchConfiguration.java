/*
 * The MIT License
 *
 *  Copyright (c) 2012, benas (md.benhassine@gmail.com)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package net.benas.cb4j.core.config;

import net.benas.cb4j.core.api.*;
import net.benas.cb4j.core.impl.*;
import net.benas.cb4j.core.jmx.BatchMonitor;
import net.benas.cb4j.core.util.BatchConstants;
import net.benas.cb4j.core.util.LogFormatter;
import net.benas.cb4j.core.util.RecordType;
import net.benas.cb4j.core.util.ReportFormatter;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

/**
 * Batch configuration class.<br/>
 *
 * This class should be used to provide all configuration parameters and mandatory implementations to run CB4J engine.
 *
 * @author benas (md.benhassine@gmail.com)
 */
public class BatchConfiguration {

    /**
     * CB4J logger.
     */
    protected final Logger logger = Logger.getLogger(BatchConstants.LOGGER_CB4J);

    /*
     * Configuration parameters.
     */
    protected Properties configurationProperties;

    /*
     * Validators and CB4J services that will be used by the engine.
     */
    protected Map<Integer, List<FieldValidator>> fieldValidators;

    private RecordReader recordReader;

    private RecordParser recordParser;

    private RecordValidator recordValidator;

    private RecordProcessor recordProcessor;

    private RecordMapper recordMapper;

    private BatchReporter batchReporter;

    private BatchMonitor batchMonitor;

    private RollBackHandler rollBackHandler;

    /**
     * Initialize configuration from a properties file.
     * @param configurationFile the configuration file name
     * @throws BatchConfigurationException thrown if :
     * <ul>
     *     <li>The configuration file is not found</li>
     *     <li>The configuration file cannot be read</li>
     * </ul>
     * This constructor is used only by subclasses to check if configuration file is specified.
     */
    protected BatchConfiguration(final String configurationFile) throws BatchConfigurationException {
        if (configurationFile == null) {
            String error = "Configuration failed : configuration file not specified";
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }
        logger.config("Configuration file specified : " + configurationFile);

        fieldValidators = new HashMap<Integer, List<FieldValidator>>();
    }

    /**
     *  Initialize configuration from a properties object.
     * @param properties the properties object to load
     */
    public BatchConfiguration(final Properties properties) {
        configurationProperties = properties;
        fieldValidators = new HashMap<Integer, List<FieldValidator>>();
    }

    /**
     * Configure the batch engine.
     * @throws BatchConfigurationException thrown if :
     * <ul>
     *     <li>One of the mandatory parameters is not specified, please refer to the reference documentation for all parameters details</li>
     *     <li>Log files for ignored and rejected records cannot be used</li>
     *     <li>One of the mandatory services is not specified, please refer to the reference documentation for all mandatory services implementations</li>
     * </ul>
     */
    public void configure() throws BatchConfigurationException {

        /*
         * Configure CB4J logger
         */
        configureCB4JLogger();

        logger.info("Configuration started at : " + new Date());

        /*
         * Configure record reader
         */
        configureRecordReader();

        /*
        * Configure record parser
        */
        configureRecordParser();

        /*
         * Configure loggers for ignored/rejected/error records
         */
        configureRecordsLoggers();

        /*
         * Configure batch reporter : if no custom reporter registered, use default implementation
         */
        if (batchReporter == null) {
            batchReporter = new DefaultBatchReporterImpl();
        }
        batchReporter.init();

        /*
         * Configure record validator with provided validators : if no custom validator registered, use default implementation
         */
        if (recordValidator == null) {
            recordValidator = new DefaultRecordValidatorImpl(fieldValidators);
        }

        /*
         * Check record mapper
         */
        if (recordMapper == null) {
            String error = "Configuration failed : no record mapper registered";
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

        /*
         * Check record processor
         */
        if (recordProcessor == null) {
            String error = "Configuration failed : no record processor registered";
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

        /*
        * register JMX MBean
        */
        configureJmxMBean();

        logger.info("Configuration successful");
        logger.info("Configuration parameters details : " + configurationProperties);

    }

    /**
     * Configure loggers for ignored/rejected/errors records.
     * @throws BatchConfigurationException thrown if loggers for ignored/rejected/errors records are not correctly configured
     */
    private void configureRecordsLoggers() throws BatchConfigurationException {

        String inputDataProperty = configurationProperties.getProperty(BatchConstants.INPUT_DATA_PATH);

        ReportFormatter reportFormatter = new ReportFormatter();

        //ignored records logger
        String outputIgnored = configurationProperties.getProperty(BatchConstants.OUTPUT_DATA_IGNORED);
        if (outputIgnored == null || (outputIgnored.length() == 0)) {
            outputIgnored = BatchConfigurationUtil.removeExtension(inputDataProperty) + BatchConstants.DEFAULT_IGNORED_SUFFIX;
            logger.warning("No log file specified for ignored records, using default : " + outputIgnored);
        }
        try {
            FileHandler ignoredRecordsHandler = new FileHandler(outputIgnored);
            ignoredRecordsHandler.setFormatter(reportFormatter);
            Logger ignoredRecordsReporter = Logger.getLogger(BatchConstants.LOGGER_CB4J_IGNORED);
            ignoredRecordsReporter.addHandler(ignoredRecordsHandler);
        } catch (IOException e) {
            String error = "Unable to use file for ignored records : " + outputIgnored;
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

        //rejected errors logger
        String outputRejected = configurationProperties.getProperty(BatchConstants.OUTPUT_DATA_REJECTED);
        if (outputRejected == null || (outputRejected.length() == 0)) {
            outputRejected = BatchConfigurationUtil.removeExtension(inputDataProperty) + BatchConstants.DEFAULT_REJECTED_SUFFIX;
            logger.warning("No log file specified for rejected records, using default : " + outputRejected);
        }
        try {
            FileHandler rejectedRecordsHandler = new FileHandler(outputRejected);
            rejectedRecordsHandler.setFormatter(reportFormatter);
            Logger rejectedRecordsReporter = Logger.getLogger(BatchConstants.LOGGER_CB4J_REJECTED);
            rejectedRecordsReporter.addHandler(rejectedRecordsHandler);
        } catch (IOException e) {
            String error = "Unable to use file for rejected records : " + outputRejected;
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

        //errors record logger
        String outputErrors = configurationProperties.getProperty(BatchConstants.OUTPUT_DATA_ERRORS);
        if (outputErrors == null || (outputErrors.length() == 0)) {
            outputErrors = BatchConfigurationUtil.removeExtension(inputDataProperty) + BatchConstants.DEFAULT_ERRORS_SUFFIX;
            logger.warning("No log file specified for error records, using default : " + outputErrors);
        }
        try {
            FileHandler errorRecordsHandler = new FileHandler(outputErrors);
            errorRecordsHandler.setFormatter(reportFormatter);
            Logger errorRecordsReporter = Logger.getLogger(BatchConstants.LOGGER_CB4J_ERRORS);
            errorRecordsReporter.addHandler(errorRecordsHandler);
        } catch (IOException e) {
            String error = "Unable to use file for error records : " + outputErrors;
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

    }

    /**
     * Configure CB4J record parser.
     * @throws BatchConfigurationException thrown if record parser is not correctly configured
     */
    private void configureRecordParser() throws BatchConfigurationException {

        //read record type property and set default value if invalid input
        String recordTypeProperty = configurationProperties.getProperty(BatchConstants.INPUT_RECORD_TYPE);
        String recordType;
        if (recordTypeProperty == null || recordTypeProperty.length() == 0) {
            recordType = BatchConstants.DEFAULT_RECORD_TYPE;
            logger.warning("Record type property not specified, records will be considered as delimiter-separated values");
        } else if (!RecordType.DSV.toString().equalsIgnoreCase(recordTypeProperty) && !RecordType.FLR.toString().equalsIgnoreCase(recordTypeProperty)) {
            recordType = BatchConstants.DEFAULT_RECORD_TYPE;
            logger.warning("Record type property '" + recordTypeProperty +"' is invalid, records will be considered as delimiter-separated values");
        } else {
            recordType = recordTypeProperty;
        }

        // fixed length record configuration
        if (RecordType.FLR.toString().equalsIgnoreCase(recordType)) {
            String fieldsLengthProperties = configurationProperties.getProperty(BatchConstants.INPUT_FIELD_LENGTHS);
            if ( fieldsLengthProperties == null || fieldsLengthProperties.length() == 0) {
                String error = "Configuration failed : when using fixed length records, fields length values property '" + BatchConstants.INPUT_FIELD_LENGTHS + "' is mandatory but was not specified.";
                logger.severe(error);
                throw new BatchConfigurationException(error);
            } else {
                //parse fields length property and extract numeric values
                StringTokenizer stringTokenizer = new StringTokenizer(fieldsLengthProperties,",");
                int[] fieldsLength = new int[stringTokenizer.countTokens()];
                int index = 0;
                while(stringTokenizer.hasMoreTokens()) {
                    String length = stringTokenizer.nextToken();
                    try {
                        fieldsLength[index] = Integer.parseInt(length);
                        index++;
                    } catch (NumberFormatException e) {
                        String error = "Configuration failed : field length '" + length + "' in property " + BatchConstants.INPUT_FIELD_LENGTHS + "=" + fieldsLengthProperties + " is not numeric.";
                        logger.severe(error);
                        throw new BatchConfigurationException(error);
                    }
                }
                recordParser = new FlrRecordParserImpl(fieldsLength);
            }
        }
        else { //delimited values configuration

        String recordSizeProperty = configurationProperties.getProperty(BatchConstants.INPUT_RECORD_SIZE);

        try {

            if (recordSizeProperty == null || (recordSizeProperty != null && recordSizeProperty.length() == 0)) {
                String error = "Record size property is mandatory but was not specified";
                logger.severe(error);
                throw new BatchConfigurationException(error);
            }

            int recordSize = Integer.parseInt(recordSizeProperty);

            String fieldsDelimiter = configurationProperties.getProperty(BatchConstants.INPUT_FIELD_DELIMITER);
            if (fieldsDelimiter == null || (fieldsDelimiter != null && fieldsDelimiter.length() == 0)) {
                fieldsDelimiter = BatchConstants.DEFAULT_FIELD_DELIMITER;
                logger.warning("No field delimiter specified, using default : '" + fieldsDelimiter + "'");
            }

            String trimWhitespacesProperty = configurationProperties.getProperty(BatchConstants.INPUT_FIELD_TRIM);
            boolean trimWhitespaces;
            if (trimWhitespacesProperty != null) {
                trimWhitespaces = Boolean.valueOf(trimWhitespacesProperty);
            } else {
                trimWhitespaces = BatchConstants.DEFAULT_FIELD_TRIM;
                logger.warning("Trim whitespaces property not specified, default to true");
            }

            String dataQualifierCharacterProperty = configurationProperties.getProperty(BatchConstants.INPUT_FIELD_QUALIFIER_CHAR);
            String dataQualifierCharacter = BatchConstants.DEFAULT_FIELD_QUALIFIER_CHAR;
            if (dataQualifierCharacterProperty != null && dataQualifierCharacterProperty.length() > 0) {
                dataQualifierCharacter = dataQualifierCharacterProperty;
            }

            logger.config("Record size specified : " + recordSize);
            logger.config("Fields delimiter specified : '" + fieldsDelimiter + "'");
            logger.config("Data qualifier character specified : '" + dataQualifierCharacter + "'");
            recordParser = new DsvRecordParserImpl(recordSize, fieldsDelimiter, trimWhitespaces, dataQualifierCharacter);

        } catch (NumberFormatException e) {
            String error = "Record size property is not recognized as a number : " + recordSizeProperty;
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }
        }
    }

    /**
     * Configure CB4J record reader.
     * @throws BatchConfigurationException thrown if record reader is not correctly configured
     */
    private void configureRecordReader() throws BatchConfigurationException {

        String inputDataProperty = configurationProperties.getProperty(BatchConstants.INPUT_DATA_PATH);
        String encodingProperty = configurationProperties.getProperty(BatchConstants.INPUT_DATA_ENCODING);
        String skipHeaderProperty = configurationProperties.getProperty(BatchConstants.INPUT_DATA_SKIP_HEADER);

        //check if input data file is specified
        if (inputDataProperty == null) {
            String error = "Configuration failed : input data file is mandatory but was not specified";
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }

        try {

            boolean skipHeader;
            if (skipHeaderProperty != null) {
                skipHeader = Boolean.valueOf(skipHeaderProperty);
            } else {
                skipHeader = BatchConstants.DEFAULT_SKIP_HEADER;
                logger.warning("Skip header property not specified, default to false");
            }

            String encoding;
            if (encodingProperty == null || (encodingProperty.length() == 0)) {
                encoding = BatchConstants.DEFAULT_FILE_ENCODING;
                logger.warning("No encoding specified for input data, using system default encoding : " + encoding);
            } else {
                if (Charset.availableCharsets().get(encodingProperty) == null || !Charset.isSupported(encodingProperty)) {
                    encoding = BatchConstants.DEFAULT_FILE_ENCODING;
                    logger.warning("Encoding '" + encodingProperty + "' not supported, using system default encoding : " + encoding);
                } else {
                    encoding = encodingProperty;
                    logger.config("Using '" + encoding + "' encoding for input file reading");
                }
            }
            recordReader = new RecordReaderImpl(inputDataProperty, encoding, skipHeader);
            logger.config("Data input file : " + inputDataProperty);
        } catch (FileNotFoundException e) {
            String error = "Configuration failed : input data file '" + inputDataProperty + "' could not be opened";
            logger.severe(error);
            throw new BatchConfigurationException(error);
        }
    }

    /*
    * Configure JMX MBean
    */
    private void configureJmxMBean() {

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name;
        try {
            name = new ObjectName("net.benas.cb4j.jmx:type=BatchMonitorMBean");
            if (!mbs.isRegistered(name)) {
                batchMonitor = new BatchMonitor(batchReporter);
                mbs.registerMBean(batchMonitor, name);
                logger.info("CB4J JMX MBean registered successfully as: " + name.getCanonicalName());
            }
        } catch (Exception e) {
            String error = "Unable to register CB4J JMX MBean. Root exception is :" + e.getMessage();
            logger.warning(error);
        }
    }

    /**
     * Configure CB4J logger.
     */
    private void configureCB4JLogger() {
        logger.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new LogFormatter());
        if (logger.getHandlers().length == 0) {
            logger.addHandler(consoleHandler);
        }
    }

    /*
     * methods used to register mandatory implementations
     */

    /**
     * Register one validator for a field.
     * @param index the field index
     * @param validator the {@link FieldValidator} of the field
     */
    public void registerFieldValidator(final int index, final FieldValidator validator) {
        List<FieldValidator> validators = fieldValidators.get(index);
        if (validators == null) {
            validators = new ArrayList<FieldValidator>();
        }
        validators.add(validator);
        fieldValidators.put(index, validators);
    }

    /**
     * Register multiple validators for a field.
     * @param index the field index
     * @param validators the list of validators used to validate the field
     */
    public void registerFieldValidators(int index, final List<FieldValidator> validators) {
        fieldValidators.put(index, validators);
    }

    /**
     * Register an implementation of {@link RecordProcessor} that will be used to process records.
     * @param recordProcessor the record processor implementation
     */
    public void registerRecordProcessor(RecordProcessor recordProcessor) {
        this.recordProcessor = recordProcessor;
    }

    /**
     * Register a custom implementation of {@link RecordValidator} that will be used to validate records.
     * @param recordValidator the custom validator implementation
     */
    public void registerRecordValidator(RecordValidator recordValidator) {
        this.recordValidator = recordValidator;
    }

    /**
     * Register an implementation of {@link RecordMapper} that will be used to map records to objects.
     * @param recordMapper the record mapper implementation
     */
    public void registerRecordMapper(RecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /**
     * Register a custom implementation of {@link BatchReporter} that will be used to ignore/reject records and generate batch report.
     * @param batchReporter the custom batch reporter implementation
     */
    public void registerBatchReporter(BatchReporter batchReporter) {
        this.batchReporter = batchReporter;
    }

    /**
     * Register a rollback handler for record processing.
     * @param rollBackHandler the rollback handler to register
     */
    public void registerRollBackHandler(RollBackHandler rollBackHandler) {
        this.rollBackHandler = rollBackHandler;
    }

    /*
    * Getters for CB4J services and parameters used by the engine
    */
    public RecordProcessor getRecordProcessor() {
        return recordProcessor;
    }

    public RecordReader getRecordReader() {
        return recordReader;
    }

    public RecordParser getRecordParser() {
        return recordParser;
    }

    public RecordValidator getRecordValidator() {
        return recordValidator;
    }

    public BatchReporter getBatchReporter() {
        return batchReporter;
    }

    public RecordMapper getRecordMapper() {
        return recordMapper;
    }

    public boolean getAbortOnFirstReject() {
        return Boolean.valueOf(configurationProperties.getProperty(BatchConstants.OUTPUT_DATA_ABORT_ON_FIRST_REJECT));
    }

    public BatchMonitor getBatchMonitor() {
        return batchMonitor;
    }

    public RollBackHandler getRollBackHandler() {
        return rollBackHandler;
    }
}
