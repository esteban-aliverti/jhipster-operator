package tech.jhipster.operator;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import tech.jhipster.operator.app.AppService;
import tech.jhipster.operator.core.K8SCoreRuntime;
import tech.jhipster.operator.crds.app.*;
import tech.jhipster.operator.crds.gateway.DoneableGateway;
import tech.jhipster.operator.crds.gateway.Gateway;
import tech.jhipster.operator.crds.gateway.GatewayList;
import tech.jhipster.operator.crds.microservice.DoneableMicroService;
import tech.jhipster.operator.crds.microservice.MicroService;
import tech.jhipster.operator.crds.microservice.MicroServiceList;
import tech.jhipster.operator.crds.registry.DoneableRegistry;
import tech.jhipster.operator.crds.registry.Registry;
import tech.jhipster.operator.crds.registry.RegistryList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.jhipster.operator.app.AppCRDs;
import tech.jhipster.operator.jdl.JDLParser;
import tech.jhipster.operator.jdl.JHipsterApplicationDefinition;

import java.util.*;

@Service
public class AppsOperator {

    // Is the service On?
    private boolean on = true;
    private boolean initDone = false;
    private boolean crdsFound = false;

    private Logger logger = LoggerFactory.getLogger(AppsOperator.class);
    private CustomResourceDefinition microServiceCRD = null;
    private CustomResourceDefinition gatewayCRD = null;
    private CustomResourceDefinition registryCRD = null;
    private CustomResourceDefinition applicationCRD = null;
    private boolean microServiceWatchRegistered = false;
    private boolean gatewayWatchRegistered = false;
    private boolean registryWatchRegistered = false;
    private boolean applicationWatchRegistered = false;

    private String appsResourceVersion;
    private String microServicesResourceVersion;
    private String registriesResourceVersion;
    private String gatewaysResourceVersion;

    private NonNamespaceOperation<Application, ApplicationList, DoneableApplication, Resource<Application, DoneableApplication>> appCRDClient;
    private NonNamespaceOperation<MicroService, MicroServiceList, DoneableMicroService, Resource<MicroService, DoneableMicroService>> microServicesCRDClient;
    private NonNamespaceOperation<Gateway, GatewayList, DoneableGateway, Resource<Gateway, DoneableGateway>> gatewaysCRDClient;
    private NonNamespaceOperation<Registry, RegistryList, DoneableRegistry, Resource<Registry, DoneableRegistry>> registriesCRDClient;


    @Autowired
    private AppService appService;

    @Autowired
    private K8SCoreRuntime k8SCoreRuntime;

    public void bootstrap() {
        crdsFound = areRequiredCRDsPresent();
        if (crdsFound) {
            initDone = init();
        }
    }

    /*
     * Check for Required CRDs
     */
    public boolean areRequiredCRDsPresent() {
        try {
            //@TODO: rename microservice to MicroService to follow JHipster semantics
            k8SCoreRuntime.registerCustomKind(AppCRDs.APP_CRD_GROUP + "/v1", "MicroService", MicroService.class);
            k8SCoreRuntime.registerCustomKind(AppCRDs.APP_CRD_GROUP + "/v1", "Gateway", Gateway.class);
            k8SCoreRuntime.registerCustomKind(AppCRDs.APP_CRD_GROUP + "/v1", "Registry", Registry.class);
            k8SCoreRuntime.registerCustomKind(AppCRDs.APP_CRD_GROUP + "/v1", "Application", Application.class);

            CustomResourceDefinitionList crds = k8SCoreRuntime.getCustomResourceDefinitionList();
            for (CustomResourceDefinition crd : crds.getItems()) {
                ObjectMeta metadata = crd.getMetadata();
                if (metadata != null) {
                    String name = metadata.getName();
                    if (AppCRDs.MICROSERVICE_CRD_NAME.equals(name)) {
                        microServiceCRD = crd;
                    }
                    if (AppCRDs.GATEWAY_CRD_NAME.equals(name)) {
                        gatewayCRD = crd;
                    }
                    if (AppCRDs.REGISTRY_CRD_NAME.equals(name)) {
                        registryCRD = crd;
                    }
                    if (AppCRDs.APP_CRD_NAME.equals(name)) {
                        applicationCRD = crd;
                    }
                }
            }
            if (allCRDsFound()) {
                logger.info("\t > App CRD: " + applicationCRD.getMetadata().getName());
                logger.info("\t > MicroService CRD: " + microServiceCRD.getMetadata().getName());
                logger.info("\t > Registry CRD: " + registryCRD.getMetadata().getName());
                logger.info("\t > Gateway CRD: " + gatewayCRD.getMetadata().getName());
                return true;
            } else {
                logger.error("> Custom CRDs required to work not found please check your installation!");
                logger.error("\t > App CRD: " + ((applicationCRD == null) ? " NOT FOUND " : applicationCRD.getMetadata().getName()));
                logger.error("\t > MicroService CRD: " + ((microServiceCRD == null) ? " NOT FOUND " : microServiceCRD.getMetadata().getName()));
                logger.error("\t > Registry CRD: " + ((registryCRD == null) ? " NOT FOUND " : registryCRD.getMetadata().getName()));
                logger.error("\t > Gateway CRD: " + ((gatewayCRD == null) ? " NOT FOUND " : gatewayCRD.getMetadata().getName()));
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("> Init sequence not done");
        }
        return false;
    }

    /*
     * Init can only be called if all the required CRDs are present
     *  - It creates the CRD clients to be able to watch and execute operations
     *  - It loads the existing resources (current state in the cluster)
     *  - It register the watches for our CRDs
     */
    public boolean init() {
        logger.info("> JHipster K8s Operator is Starting!");
        // Creating CRDs Clients
        appCRDClient = k8SCoreRuntime.customResourcesClient(applicationCRD, Application.class, ApplicationList.class, DoneableApplication.class).inNamespace(k8SCoreRuntime.getNamespace());
        microServicesCRDClient = k8SCoreRuntime.customResourcesClient(microServiceCRD, MicroService.class, MicroServiceList.class, DoneableMicroService.class).inNamespace(k8SCoreRuntime.getNamespace());
        gatewaysCRDClient = k8SCoreRuntime.customResourcesClient(gatewayCRD, Gateway.class, GatewayList.class, DoneableGateway.class).inNamespace(k8SCoreRuntime.getNamespace());
        registriesCRDClient = k8SCoreRuntime.customResourcesClient(registryCRD, Registry.class, RegistryList.class, DoneableRegistry.class).inNamespace(k8SCoreRuntime.getNamespace());

        if (loadExistingResources() && watchOurCRDs()) {
            return true;
        }

        return false;

    }

    /*
     * Check that all the CRDs are found for this operator to work
     */
    private boolean allCRDsFound() {
        if (microServiceCRD == null || applicationCRD == null || gatewayCRD == null || registryCRD == null) {
            return false;
        }
        return true;
    }

    /*
     * Watch our CRDs
     *  Register watches if they were not registered yet
     */
    private boolean watchOurCRDs() {
        // Watch for our CRDs
        if (!microServiceWatchRegistered) {
            registerMicroServiceWatch();
        }
        if (!registryWatchRegistered) {
            registerRegistryWatch();
        }
        if (!gatewayWatchRegistered) {
            registerGatewayWatch();
        }
        if (!applicationWatchRegistered) {
            registerApplicationWatch();
        }
        if (areAllCRDWatchesRegistered()) {
            logger.info("> All CRDs Found, init complete");
            return true;
        } else {
            logger.error("> CRDs missing, check your installation and run init again");
            return false;
        }
    }

    /*
     * Load existing instances of our CRDs
     *  - This checks the existing resources and make sure that they are loaded correctly
     *  - This also performs the binding of a service to its app
     */
    private boolean loadExistingResources() {
        // Load Existing Applications
        List<Application> applicationList = appCRDClient.list().getItems();
        if (!applicationList.isEmpty()) {
            appsResourceVersion = applicationList.get(0).getMetadata().getResourceVersion();
            logger.info(">> Applications Resource Version: " + appsResourceVersion);
            applicationList.forEach(app -> {
                appService.addApp(app.getMetadata().getName(), app);
                logger.info("> App " + app.getMetadata().getName() + " found.");
            });

        }
        // Load Existing Service As
        List<MicroService> microServiceList = microServicesCRDClient.list().getItems();
        if (!microServiceList.isEmpty()) {
            microServicesResourceVersion = microServiceList.get(0).getMetadata().getResourceVersion();
            logger.info(">> MicroService Resource Version: " + microServicesResourceVersion);
            microServiceList.forEach(microService -> {
                appService.addMicroServiceToApp(microService);
            });
        }
        // Load Existing Gateways
        List<Gateway> gatewayList = gatewaysCRDClient.list().getItems();
        if (!gatewayList.isEmpty()) {
            gatewaysResourceVersion = gatewayList.get(0).getMetadata().getResourceVersion();
            logger.info(">> Gateway Resource Version: " + gatewaysResourceVersion);
            gatewayList.forEach(gateway -> {
                appService.addGatewayToApp(gateway);
            });

        }
        // Load Existing Registries
        List<Registry> registriesList = registriesCRDClient.list().getItems();
        if (!registriesList.isEmpty()) {
            registriesResourceVersion = registriesList.get(0).getMetadata().getResourceVersion();
            logger.info(">> Registry Resource Version: " + registriesResourceVersion);
            registriesList.forEach(registry -> {
                appService.addRegistryToApp(registry);
            });

        }
        return true;
    }


    /*
     * Check that all the CRDs are being watched for changes
     */
    private boolean areAllCRDWatchesRegistered() {
        if (applicationWatchRegistered && microServiceWatchRegistered && gatewayWatchRegistered && registryWatchRegistered) {
            return true;
        }
        return false;
    }

    /*
     * Register Application Watch
     *  - This watch is in charge of adding and removing apps to/from the In memory desired state
     */
    private void registerApplicationWatch() {
        logger.info("> Registering Application CRD Watch");
        appCRDClient.withResourceVersion(appsResourceVersion).watch(new Watcher<Application>() {
            @Override
            public void eventReceived(Watcher.Action action, Application application) {
                if (action.equals(Action.ADDED)) {
                    logger.info(">> Adding App: " + application.getMetadata().getName());
                    appService.addApp(application.getMetadata().getName(), application);
                    List<MicroService> microServiceForAppList = microServicesCRDClient.withLabel("app", application.getMetadata().getName()).list().getItems();
                    if (microServiceForAppList != null && !microServiceForAppList.isEmpty()) {
                        microServiceForAppList.forEach(microService -> {
                            appService.addMicroServiceToApp(microService);
                        });
                    }
                    List<Gateway> gatewayForAppList = gatewaysCRDClient.withLabel("app", application.getMetadata().getName()).list().getItems();
                    if (gatewayForAppList != null && !gatewayForAppList.isEmpty()) {
                        gatewayForAppList.forEach(gateway -> {
                            appService.addGatewayToApp(gateway);
                        });
                    }
                    List<Registry> registryForAppList = registriesCRDClient.withLabel("app", application.getMetadata().getName()).list().getItems();
                    if (registryForAppList != null && !registryForAppList.isEmpty()) {
                        registryForAppList.forEach(registry -> {
                            appService.addRegistryToApp(registry);
                        });
                    }
                }
                if (action.equals(Action.DELETED)) {
                    logger.info(">> Deleting App: " + application.getMetadata().getName());
                    appService.removeApp(application.getMetadata().getName());
                }

                if (application.getSpec() == null) {
                    logger.info("No Spec for resource " + application.getMetadata().getName());
                }
            }

            @Override
            public void onClose(KubernetesClientException cause) {
            }
        });
        applicationWatchRegistered = true;

    }

    /*
     * Register Registry Watch
     */

    private void registerMicroServiceWatch() {
        logger.info("> Registering MicroService CRD Watch");
        microServicesCRDClient.withResourceVersion(microServicesResourceVersion).watch(new Watcher<MicroService>() {
            @Override
            public void eventReceived(Watcher.Action action, MicroService microService) {
                if (action.equals(Action.ADDED)) {
                    appService.addMicroServiceToApp(microService);
                }
                if (action.equals(Action.DELETED)) {
                    appService.removeMicroServiceFromApp(microService);
                }
                if (microService.getSpec() == null) {
                    logger.error("No Spec for resource " + microService);
                }
            }

            @Override
            public void onClose(KubernetesClientException cause) {
            }
        });
        microServiceWatchRegistered = true;
    }

    /*
     * Register Service A Watch
     */

    private void registerRegistryWatch() {
        logger.info("> Registering Registry CRD Watch");
        registriesCRDClient.withResourceVersion(registriesResourceVersion).watch(new Watcher<Registry>() {
            @Override
            public void eventReceived(Watcher.Action action, Registry registry) {
                if (action.equals(Action.ADDED)) {
                    appService.addRegistryToApp(registry);

                }
                if (action.equals(Action.DELETED)) {
                    appService.removeRegistryFromApp(registry);

                }
                if (registry.getSpec() == null) {
                    logger.error("No Spec for resource " + registry);
                }
            }

            @Override
            public void onClose(KubernetesClientException cause) {
            }
        });
        registryWatchRegistered = true;
    }

    private void registerGatewayWatch() {
        logger.info("> Registering Gateway CRD Watch");
        gatewaysCRDClient.withResourceVersion(gatewaysResourceVersion).watch(new Watcher<Gateway>() {
            @Override
            public void eventReceived(Watcher.Action action, Gateway gateway) {
                if (action.equals(Action.ADDED)) {
                    appService.addGatewayToApp(gateway);

                }
                if (action.equals(Action.DELETED)) {
                    appService.removeGatewayFromApp(gateway);

                }
                if (gateway.getSpec() == null) {
                    logger.error("No Spec for resource " + gateway);
                }
            }

            @Override
            public void onClose(KubernetesClientException cause) {
            }
        });
        gatewayWatchRegistered = true;
    }

    /*
     * Reconcile contains the logic that understand how services relates to applications and the application state
     *   matches the desired state with current state in K8s
     */
    public void reconcile() {
        if (appService.getApps().isEmpty()) {
            logger.info("> No Healthy Apps found.");
        }
        // For each App Desired State
        appService.getAppsMap().keySet().forEach(appName ->
                {
                    Application app = appService.getApp(appName);
                    logger.info("> Scanning App: " + appName + "...");
                    if (appService.isAppHealthy(app)) {
                        logger.info("> App Name: " + appName + " is up and running");
                        app.getSpec().getMicroservices().forEach(m -> logger.info("\t> MicroService found: " + m));
                        app.getSpec().setStatus("HEALTHY");
                        String externalIp = k8SCoreRuntime.findExternalIP();
                        String url = "http://" + externalIp + "/apps/" + app.getMetadata().getName() + "/" + app.getSpec().getVersion() + "/";
                        appService.addAppUrl(app.getMetadata().getName(), url);
                        app.getSpec().setUrl(url);
                        logger.info("> App: " + appName + ", status:  HEALTHY, URL: " + url + " \n");
                    } else {
                        logger.error("> App Name: " + appName + " is down due missing services");
                        if (app.getSpec().getMicroservices() == null || app.getSpec().getMicroservices().isEmpty()) {
                            logger.info("App: " + appName + ": No MicroService found. ");
                        } else {
                            app.getSpec().getMicroservices().forEach(m -> logger.info("\t> MicroService found: " + m));
                        }
                        app.getSpec().setStatus("UNHEALTHY");
                        app.getSpec().setUrl("N/A");
                        logger.info("> App: " + appName + ", status: UNHEALTHY. \n ");
                    }
                    appCRDClient.createOrReplace(app);
                }
        );

    }


    public CustomResourceDefinition getMicroServiceCRD() {
        return microServiceCRD;
    }

    public CustomResourceDefinition getGatewayCRD() {
        return gatewayCRD;
    }

    public CustomResourceDefinition getRegistryCRD() {
        return registryCRD;
    }

    public CustomResourceDefinition getApplicationCRD() {
        return applicationCRD;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    public boolean isInitDone() {
        return initDone;
    }


    //@TODO: refactor this nightmare :)
    public void newApp(JHipsterApplicationDefinition appDefinition) {
        Application app = new Application();
        ObjectMeta objectMeta = new ObjectMeta();
        objectMeta.setName(appDefinition.getName());
        objectMeta.setAdditionalProperty("jdl", appDefinition.getJDLContent());
        objectMeta.setFinalizers(Arrays.asList("foregroundDeletion"));
        app.setMetadata(objectMeta);
        ApplicationSpec spec = new ApplicationSpec();
        spec.setAppDefinition(appDefinition);
        spec.setVersion(appDefinition.getVersion());
        app.setSpec(spec);

        Application storedApp = appCRDClient.create(app);


        OwnerReference ownerReference = new OwnerReference();
        ownerReference.setUid(storedApp.getMetadata().getUid());
        ownerReference.setName(storedApp.getMetadata().getName());
        ownerReference.setKind(storedApp.getKind());
        ownerReference.setController(true);
        ownerReference.setBlockOwnerDeletion(true);
        ownerReference.setApiVersion(storedApp.getApiVersion());
        List<OwnerReference> ownerReferences = new ArrayList<>();
        ownerReferences.add(ownerReference);

        Map<String, String> labels = new HashMap<>();
        labels.put("app", appDefinition.getName());

        // By default I will create a Registry to make the App Complete
        Registry registry = new Registry();
        ObjectMeta objectMetaRegistry = new ObjectMeta();
        objectMetaRegistry.setName("jhipster-registry");
        objectMetaRegistry.setOwnerReferences(ownerReferences);
        objectMetaRegistry.setFinalizers(Arrays.asList("foregroundDeletion"));
        objectMetaRegistry.setLabels(labels);
        registry.setMetadata(objectMetaRegistry);
        ServiceSpec registrySpec = new ServiceSpec();
        registrySpec.setServiceName("jhipster-registry");
        registrySpec.setServiceVersion("1.0");
        registrySpec.setServicePort("8761"); //hardcoded in jhipster k8s scripts and yamls
        registry.setSpec(registrySpec);


        registriesCRDClient.create(registry);


        appDefinition.getModules().forEach(md -> {
                    if (JDLParser.fromJDLServiceToKind(md.getType()).equals("Gateway")) {
                        Gateway gateway = new Gateway();
                        ObjectMeta objectMetaGateway = new ObjectMeta();
                        objectMetaGateway.setName(md.getName());
                        objectMetaGateway.setOwnerReferences(ownerReferences);
                        objectMetaGateway.setFinalizers(Arrays.asList("foregroundDeletion"));
                        objectMetaGateway.setLabels(labels);
                        gateway.setMetadata(objectMetaGateway);
                        ServiceSpec gatewaySpec = new ServiceSpec();
                        gatewaySpec.setServiceName(md.getName());
                        gatewaySpec.setServiceVersion("1.0");
                        if (md.getPort() == null || md.getPort().isEmpty()) {
                            gatewaySpec.setServicePort("8080");
                        } else {
                            gatewaySpec.setServicePort(md.getPort());
                        }
                        gateway.setSpec(gatewaySpec);
                        gatewaysCRDClient.create(gateway);
                    } else {

                        MicroService microService = new MicroService();
                        ObjectMeta objectMetaMicroService = new ObjectMeta();
                        objectMetaMicroService.setName(md.getName());

                        objectMetaMicroService.setOwnerReferences(ownerReferences);
                        objectMetaMicroService.setLabels(labels);
                        objectMetaMicroService.setFinalizers(Arrays.asList("foregroundDeletion"));
                        microService.setMetadata(objectMetaMicroService);


                        ServiceSpec serviceSpec = new ServiceSpec();
                        serviceSpec.setServiceName(md.getName());
                        serviceSpec.setServiceVersion("1.0");
                        serviceSpec.setServicePort(md.getPort());
                        microService.setSpec(serviceSpec);

                        microServicesCRDClient.create(microService);
                    }
                }
        );

    }

    public void deleteApp(String appName) {
        Application app = appService.getApp(appName);
        //@TODO: delete by API doesn't cascade yet..
        appCRDClient.delete(app);
    }


}
