package com.ReEncryptUtility.ReEncryptUtility.service;

import com.ReEncryptUtility.ReEncryptUtility.dto.CryptoManagerRequestDTO;
import com.ReEncryptUtility.ReEncryptUtility.dto.CryptoManagerResponseDTO;
import com.ReEncryptUtility.ReEncryptUtility.dto.RequestWrapper;
import com.ReEncryptUtility.ReEncryptUtility.dto.ResponseWrapper;
import com.ReEncryptUtility.ReEncryptUtility.entity.DemographicEntity;
import com.ReEncryptUtility.ReEncryptUtility.entity.DocumentEntity;
import com.ReEncryptUtility.ReEncryptUtility.repository.DemographicRepository;
import com.ReEncryptUtility.ReEncryptUtility.repository.DocumentRepository;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.commons.khazana.exception.ObjectStoreAdapterException;
import io.mosip.commons.khazana.impl.S3Adapter;
import io.mosip.commons.khazana.spi.ObjectStoreAdapter;
import io.mosip.commons.khazana.util.ObjectStoreUtil;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.fsadapter.exception.FSAdapterException;
import io.mosip.kernel.core.util.HMACUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.mosip.commons.khazana.config.LoggerConfiguration.REGISTRATIONID;
import static io.mosip.commons.khazana.config.LoggerConfiguration.SESSIONID;
import static io.mosip.commons.khazana.constant.KhazanaErrorCodes.OBJECT_STORE_NOT_ACCESSIBLE;


@Component
@RefreshScope
public class ReEncrypt {

    Logger logger = org.slf4j.LoggerFactory.getLogger(ReEncrypt.class);

    @Autowired
    RestTemplate restTemplate;

    @Value("${cryptoResource.url}")
    public String cryptoResourceUrl;

    @Value("${appId}")
    public String appId;

    @Value("${clientId}")
    public String clientId;

    @Value("${secretKey}")
    public String secretKey;

    @Value("${decryptBaseUrl}")
    public String decryptBaseUrl;

    @Value("${encryptBaseUrl}")
    public String encryptBaseUrl;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private DemographicRepository reEncryptRepository;


    @Qualifier("S3Adapter")
    @Autowired
    private ObjectStoreAdapter objectStore;

    @Value("${mosip.kernel.objectstore.account-name}")
    private String objectStoreAccountName;

    @Value("${isNewDatabase}")
    private String isNewDatabase;

    @Value("${object.store.s3.accesskey:accesskey:accesskey}")
    private String accessKey;
    @Value("${object.store.s3.secretkey:secretkey:secretkey}")
    private String objectStoreSecretKey;
    @Value("${object.store.s3.url:null}")
    private String url;

    @Value("${destinationObjectStore.s3.url}")
    private String destinationObjectStoreUrl;

    @Value("${destinationObjectStore.s3.access-key}")
    private String destinationObjectStoreAccessKey;

    @Value("${destinationObjectStore.s3.secret-key}")
    private String destinationObjectStoreSecretKey;

    String token = "";

    public int row;

    public int successFullRow;

    public List<DemographicEntity> demographicEntityList = new ArrayList<>();

    public ReEncrypt(ObjectMapper mapper, DemographicRepository reEncryptRepository) {
        this.mapper = mapper;
        this.reEncryptRepository = reEncryptRepository;
    }

    public void generateToken(String url) {
        RequestWrapper<ObjectNode> requestWrapper = new RequestWrapper<>();
        ObjectNode request = mapper.createObjectNode();
        request.put("appId", appId);
        request.put("clientId", clientId);
        request.put("secretKey", secretKey);
        requestWrapper.setRequest(request);
        ResponseEntity<ResponseWrapper> response = restTemplate.postForEntity(url + "/v1/authmanager/authenticate/clientidsecretkey", requestWrapper,
                ResponseWrapper.class);
        token = response.getHeaders().getFirst("authorization");
        restTemplate.setInterceptors(Collections.singletonList(new ClientHttpRequestInterceptor() {

            @Override
            public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                    throws java.io.IOException {
                request.getHeaders().add(HttpHeaders.COOKIE, "Authorization=" + token);
                return execution.execute(request, body);
            }
        }));
    }

    public byte[] decrypt(byte[] originalInput, LocalDateTime localDateTime, String decryptBaseUrl) throws Exception {
        logger.info("In decrypt method of CryptoUtil service ");
        ResponseEntity<ResponseWrapper<CryptoManagerResponseDTO>> response = null;
        byte[] decodedBytes = null;
        generateToken(decryptBaseUrl);
        try {
            CryptoManagerRequestDTO dto = new CryptoManagerRequestDTO();
            dto.setApplicationId("REGISTRATION");
            dto.setData(new String(originalInput, StandardCharsets.UTF_8));
            dto.setReferenceId("");
            dto.setTimeStamp(localDateTime);
            RequestWrapper<CryptoManagerRequestDTO> requestKernel = new RequestWrapper<>();
            requestKernel.setRequest(dto);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<RequestWrapper<CryptoManagerRequestDTO>> request = new HttpEntity<>(requestKernel, headers);
            logger.info("In decrypt method of CryptoUtil service cryptoResourceUrl: " + cryptoResourceUrl + "/decrypt");
            response = restTemplate.exchange(cryptoResourceUrl + "/decrypt", HttpMethod.POST, request,
                    new ParameterizedTypeReference<ResponseWrapper<CryptoManagerResponseDTO>>() {
                    });
            logger.info("myresponse\n" + response.getBody().getResponse().getData().getBytes(StandardCharsets.UTF_8));
            decodedBytes = response.getBody().getResponse().getData().getBytes();
        } catch (Exception ex) {
            logger.error("Error in decrypt method of CryptoUtil service " + ex.getMessage());
        }
        return decodedBytes;
    }

    public byte[] encrypt(byte[] originalInput, LocalDateTime localDateTime, String encryptBaseUrl) {
        logger.info("sessionId", "idType", "id", "In encrypt method of CryptoUtil service ");
        generateToken(encryptBaseUrl);
        ResponseEntity<ResponseWrapper<CryptoManagerResponseDTO>> response = null;
        byte[] encryptedBytes = null;
        try {
            CryptoManagerRequestDTO dto = new CryptoManagerRequestDTO();
            dto.setApplicationId("PRE_REGISTRATION");
            dto.setData(new String(originalInput, StandardCharsets.UTF_8));
            dto.setReferenceId("INDIVIDUAL");
            dto.setTimeStamp(localDateTime);
            dto.setPrependThumbprint(false);
            RequestWrapper<CryptoManagerRequestDTO> requestKernel = new RequestWrapper<>();
            requestKernel.setRequest(dto);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<RequestWrapper<CryptoManagerRequestDTO>> request = new HttpEntity<>(requestKernel, headers);
            logger.info("sessionId", "idType", "id",
                    "In encrypt method of CryptoUtil service cryptoResourceUrl: " + "/encrypt");
            response = restTemplate.exchange(encryptBaseUrl + "/v1/keymanager/encrypt", HttpMethod.POST, request,
                    new ParameterizedTypeReference<ResponseWrapper<CryptoManagerResponseDTO>>() {
                    });
            encryptedBytes = response.getBody().getResponse().getData().getBytes();
        } catch (Exception ex) {
            logger.error("sessionId", "idType", "id", "Error in encrypt method of CryptoUtil service " + ex.getMessage());
        }
        return encryptedBytes;
    }

    public static String hashUtill(byte[] bytes) {
        return HMACUtils.digestAsPlainText(HMACUtils.generateHash(bytes));
    }

    @Autowired
    private DemographicRepository demographicRepository;

    @Autowired
    private DocumentRepository documentRepository;



    public void start() throws Exception {
        DatabaseThreadContext.setCurrentDatabase(Database.PRIMARY);
        logger.info("sessionId", "idType", "id", "In start method of CryptoUtil service ");

//        List<DemographicEntity> applicantDemographic = demographicRepository.findAll();
//        reEncryptData(applicantDemographic);
        List<DocumentEntity> documentEntityList = documentRepository.findAll();
        reEncryptOldDocument(documentEntityList);

        if(isNewDatabase.equalsIgnoreCase("true")) {
            //InsertDataInNewDatabase();
            //insertDocumentInObjectStore();
        } else {

        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    private void insertDocumentInObjectStore() {
        logger.info("sessionId", "idType", "id", "In insertDocumentInObjectStore method of CryptoUtil service ");
        List<DemographicEntity> applicantDemographic = demographicRepository.findAll();
        logger.info("access key" + accessKey);
        logger.info("secret key" + secretKey);
        logger.info("url" + url);
        logger.info("destination object store" + destinationObjectStoreUrl);
        logger.info("destination object store access key" + destinationObjectStoreAccessKey);
        logger.info("destination object store secret key" + destinationObjectStoreSecretKey);
        BeanDefinitionRegistry beanRegistry = (BeanDefinitionRegistry) applicationContext.getAutowireCapableBeanFactory();
        BeanDefinition newBeanDefinition = BeanDefinitionBuilder.rootBeanDefinition(ObjectStoreAdapter.class).getBeanDefinition();
        accessKey = destinationObjectStoreAccessKey;
        secretKey = destinationObjectStoreSecretKey;
        url = destinationObjectStoreUrl;
        beanRegistry.registerBeanDefinition("s3Adapter", newBeanDefinition);
        accessKey = destinationObjectStoreAccessKey;
        secretKey = destinationObjectStoreSecretKey;
        url = destinationObjectStoreUrl;
        logger.info("access key" + accessKey);
        logger.info("secret key" + secretKey);
        logger.info("url" + url);

        List<DocumentEntity> documentEntityList = documentRepository.findAll();
        int counter=0;
        for (DocumentEntity documentEntities : documentEntityList) {
            if (counter++>0)
                break;
            logger.info("pre-registration-id:-" + documentEntities.getDemographicEntity().getPreRegistrationId());
            documentEntityList = documentRepository.findByDemographicEntityPreRegistrationId(documentEntities.getDemographicEntity().getPreRegistrationId());
            logger.info("Total rows found in prereg:-" + documentEntityList.size());
            if (documentEntityList != null && !documentEntityList.isEmpty()) {
                logger.info("spcific prereg id:" + documentEntityList.size());
                for (DocumentEntity documentEntity : documentEntityList) {
                    logger.info(documentEntity.getDemographicEntity().getPreRegistrationId());
                    String key = documentEntity.getDocCatCode() + "_" + documentEntity.getDocumentId();
                    try {
                        if (true) {
                                //objectStore.exists("objectStoreAccountName", documentEntity.getDemographicEntity().getPreRegistrationId(), null, null, key) == false) {
                            AmazonS3 connection = getConnection(objectStoreAccountName);
                            System.out.println("connection" + connection);
                            logger.info("key not found in objectstore");
                            continue;
                        }
                    } catch (SdkClientException  e1) {
                        logger.info("Exception:- bucket not found");
                        throw new SdkClientException("Bucket not found");
                    }
                }
            }
        }
    }



    //@Value("${object.store.s3.region:null}")
    private String region;

    //@Value("${object.store.s3.readlimit:10000000}")
    private int readlimit;

    //@Value("${object.store.connection.max.retry:20}")
    private int maxRetry=2;

    //@Value("${object.store.max.connection:200}")
    private int maxConnection=2;

    //@Value("${object.store.s3.use.account.as.bucketname:false}")
    private boolean useAccountAsBucketname;

    private int retry = 0;
    private AmazonS3 connection = null;
    private AmazonS3 getConnection(String bucketName) {
        if (connection != null)
            return connection;

        try {
            AWSCredentials awsCredentials = new BasicAWSCredentials(destinationObjectStoreAccessKey, destinationObjectStoreSecretKey);
            connection = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                    .enablePathStyleAccess().withClientConfiguration(new ClientConfiguration().withMaxConnections(maxConnection)
                            .withMaxErrorRetry(maxRetry))
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(destinationObjectStoreUrl, region)).build();
            // test connection once before returning it
            connection.doesBucketExistV2(bucketName);
            // reset retry after every successful connection so that in case of failure it starts from zero.
            retry = 0;
        } catch (Exception e) {
            if (retry >= maxRetry) {
                // reset the connection and retry count
                retry = 0;
                connection = null;
                //LOGGER.error(SESSIONID, REGISTRATIONID,"Maximum retry limit exceeded. Could not obtain connection for "+ bucketName +". Retry count :" + retry, ExceptionUtils.getStackTrace(e));
                throw new ObjectStoreAdapterException(OBJECT_STORE_NOT_ACCESSIBLE.getErrorCode(), OBJECT_STORE_NOT_ACCESSIBLE.getErrorMessage(), e);
            } else {
                connection = null;
                retry = retry + 1;
                //LOGGER.error(SESSIONID, REGISTRATIONID,"Exception occured while obtaining connection for "+ bucketName +". Will try again. Retry count : " + retry, ExceptionUtils.getStackTrace(e));
                getConnection(bucketName);
            }
        }
        return connection;
    }

    private void InsertDataInNewDatabase() {
        logger.info("sessionId", "idType", "id", "In InsertDataInNewDatabase method of CryptoUtil service ");
        DatabaseThreadContext.setCurrentDatabase(Database.SECONDARY);
        logger.info("size of list"+demographicEntityList.size());
        logger.info("size of qa=upgrade"+demographicRepository.findAll().size());

       for(DemographicEntity demographicEntity : demographicEntityList) {
           logger.info("demographicEntity prereg id : " + demographicEntity.getPreRegistrationId());
           if(demographicRepository.findBypreRegistrationId(demographicEntity.getPreRegistrationId()) == null) {
               DemographicEntity demographicEntity1 = new DemographicEntity();
               demographicEntity1.setPreRegistrationId(demographicEntity.getPreRegistrationId());
               demographicEntity1.setDemogDetailHash(demographicEntity.getDemogDetailHash());
               demographicEntity1.setEncryptedDateTime(demographicEntity.getEncryptedDateTime());
               demographicEntity1.setApplicantDetailJson(demographicEntity.getApplicantDetailJson());
               demographicEntity1.setStatusCode(demographicEntity.getStatusCode());
               demographicEntity1.setLangCode(demographicEntity.getLangCode());
               demographicEntity1.setCrAppuserId(demographicEntity.getCrAppuserId());
               demographicEntity1.setCreatedBy(demographicEntity.getCreatedBy());
               demographicEntity1.setCreateDateTime(demographicEntity.getCreateDateTime());
               demographicEntity1.setUpdatedBy(demographicEntity.getUpdatedBy());
               demographicEntity1.setUpdateDateTime(demographicEntity.getUpdateDateTime());
               demographicRepository.save(demographicEntity1);
           }
       }
        DatabaseThreadContext.setCurrentDatabase(Database.PRIMARY);
    }

    private static final String SUFFIX = "/";

    private void reEncryptOldDocument(List<DocumentEntity> documentEntityList)  {
        logger.info("Total rows:-" + documentEntityList.size());
        int objectStoreFoundCounter=0;
        for (DocumentEntity documentEntities : documentEntityList) {
            logger.info("pre-registration-id:-" + documentEntities.getDemographicEntity().getPreRegistrationId());
            documentEntityList = documentRepository.findByDemographicEntityPreRegistrationId(documentEntities.getDemographicEntity().getPreRegistrationId());
            logger.info("Total rows found in prereg:-" + documentEntityList.size());
            if (documentEntityList != null && !documentEntityList.isEmpty()) {
                logger.info("spcific prereg id:" + documentEntityList.size());
                for (DocumentEntity documentEntity : documentEntityList) {
                    logger.info(documentEntity.getDemographicEntity().getPreRegistrationId());
                    String key = documentEntity.getDocCatCode() + "_" + documentEntity.getDocumentId();
                    try {
                        if (objectStore.exists("objectStoreAccountName", documentEntity.getDemographicEntity().getPreRegistrationId(), null, null, key) == false) {
                            logger.info("key not found in objectstore");
                            continue;
                        }
                        InputStream sourcefile = objectStore.getObject("objectStoreAccountName",
                                documentEntity.getDemographicEntity().getPreRegistrationId(), null, null, key);
                        if (sourcefile != null) {
                            objectStoreFoundCounter++;
                            logger.info("sourcefile not null");
                            byte[] bytes = IOUtils.toByteArray(sourcefile);
                            byte[] decryptedBytes =  decrypt(bytes, LocalDateTime.now(), decryptBaseUrl);
                            if (decryptedBytes == null) {
                                logger.info("decryptedBytes is null");
                                continue;
                            }
                            byte[] reEncryptedBytes = encrypt(decryptedBytes, LocalDateTime.now(), encryptBaseUrl);
                            logger.info("bytes:\n" + bytes);
                            logger.info("decryptedBytes:\n" + decryptedBytes);
                            logger.info("reEncryptedBytes:\n" + (reEncryptedBytes));
                            String folderName = documentEntity.getDemographicEntity().getPreRegistrationId();
                            if(isNewDatabase.equalsIgnoreCase("true")) {
                                AmazonS3 connection = getConnection(objectStoreAccountName);
                                if (!connection.doesBucketExistV2(objectStoreAccountName))
                                    connection.createBucket(objectStoreAccountName);
                                //createFolder(objectStoreAccountName, folderName, connection);
                               String fileName = folderName + SUFFIX + key;
//  PutObjectRequest putObjectRequest = new PutObjectRequest(objectStoreAccountName,
//                folderName + SUFFIX, emptyContent, metadata);
                                connection.putObject(new PutObjectRequest(objectStoreAccountName, fileName, new ByteArrayInputStream(reEncryptedBytes), new ObjectMetadata()));
//                                s3client.putObject(new PutObjectRequest("target-bucket", "/targetsystem-folder/"+fileName, file)
//                                        .withCannedAcl(CannedAccessControlList.PublicRead));
                            }
                            else {
                                objectStore.putObject(objectStoreAccountName, documentEntity.getDemographicEntity().getPreRegistrationId(), null, null, key, new ByteArrayInputStream(reEncryptedBytes));
                            }
                            List<DocumentEntity> currentDocumentEntityList =  documentRepository.findByDemographicEntityPreRegistrationId(documentEntity.getDemographicEntity().getPreRegistrationId());
                            for (DocumentEntity currentDocumentEntity : currentDocumentEntityList) {
                                currentDocumentEntity.setDocHash(hashUtill(reEncryptedBytes));
                                currentDocumentEntity.setEncryptedDateTime(LocalDateTime.now());
                                demographicRepository.save(currentDocumentEntity.getDemographicEntity());
                                documentRepository.save(currentDocumentEntity);
                            }
                        }
                    } catch (AmazonS3Exception | FSAdapterException | IOException e) {
                        logger.info("Exception:- bucket not found");
                        throw new AmazonS3Exception("bucket not found");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    logger.info("DocumentEntity:-" + documentEntity.getDocumentId());
                }
            }
        }
        logger.info("Number of rows fetched by object store:-" + objectStoreFoundCounter);
    }

    private void createFolder(String objectStoreAccountName, String folderName, AmazonS3 connection) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(0);
        // create empty content
        InputStream emptyContent = new ByteArrayInputStream(new byte[0]);
        // create a PutObjectRequest passing the folder name suffixed by /
        PutObjectRequest putObjectRequest = new PutObjectRequest(objectStoreAccountName,
                folderName + SUFFIX, emptyContent, metadata);
        // send request to S3 to create folder
        connection.putObject(putObjectRequest);
    }


    private void reEncryptData(List<DemographicEntity> applicantDemographic) throws Exception {
        int count = 0;
        for (DemographicEntity demographicEntity : applicantDemographic) {
            if (count >6)
                break;
            logger.info("pre registration id: " + demographicEntity.getPreRegistrationId());
            logger.info("encrypted : " + new String(demographicEntity.getApplicantDetailJson()));
            if (demographicEntity.getApplicantDetailJson() != null) {
                byte[] decryptedBytes = decrypt(demographicEntity.getApplicantDetailJson(), LocalDateTime.now(), decryptBaseUrl);
                if(decryptedBytes == null)
                    continue;
                count++;
                logger.info("decrypted: " + new String(decryptedBytes));
                byte[] ReEncrypted = encrypt(decryptedBytes, LocalDateTime.now(), encryptBaseUrl);
                logger.info("ReEncrypted: " + new String(ReEncrypted));
                if(isNewDatabase.equalsIgnoreCase("true")) {
                    logger.info("I am in new database");
                    demographicEntity.setApplicantDetailJson(ReEncrypted);
                    demographicEntity.setEncryptedDateTime(LocalDateTime.now());
                    demographicEntity.setDemogDetailHash(hashUtill(ReEncrypted));
                    demographicEntityList.add(demographicEntity);
                }
                else {
                    logger.info("i am in else false condition");
                    DemographicEntity demographicEntity1 = demographicRepository.findBypreRegistrationId(demographicEntity.getPreRegistrationId());
                    demographicEntity1.setApplicantDetailJson(ReEncrypted);
                    demographicEntity1.setEncryptedDateTime(LocalDateTime.now());
                    demographicEntity1.setDemogDetailHash(hashUtill(ReEncrypted));
                    demographicRepository.save(demographicEntity1);
                }
            }
        }
        logger.info("Total rows "+ applicantDemographic.size());
        logger.info("Total rows encrypted "+ count);
    }
}



