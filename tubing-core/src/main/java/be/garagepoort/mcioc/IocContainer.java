package be.garagepoort.mcioc;

import be.garagepoort.mcioc.configuration.ConfigProperty;
import be.garagepoort.mcioc.configuration.ConfigurationLoader;
import be.garagepoort.mcioc.configuration.PropertyInjector;
import be.garagepoort.mcioc.configuration.TubingPluginInjector;
import be.garagepoort.mcioc.configuration.yaml.configuration.file.FileConfiguration;
import be.garagepoort.mcioc.load.InjectTubingPlugin;
import be.garagepoort.mcioc.load.TubingBeanAnnotationRegistrator;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IocContainer {

    private List<Class> beanAnnotations;
    private final Map<Class, Object> beans = new HashMap<>();
    private final IocConditionalPropertyFilter iocConditionalPropertyFilter = new IocConditionalPropertyFilter();
    private TubingPlugin tubingPlugin;
    private ConfigurationLoader configurationLoader;
    private ScanResult scanResult;

    public void init(TubingPlugin tubingPlugin) {
        try {
            this.tubingPlugin = tubingPlugin;
            String pkg = tubingPlugin.getClass().getPackage().getName();
            scanResult = new ClassGraph()
                .enableAllInfo()
                .acceptPackages(pkg)
                .scan();
            beanAnnotations = new ArrayList<>();
            for (Class<? extends TubingBeanAnnotationRegistrator> aClass : scanResult.getClassesImplementing(TubingBeanAnnotationRegistrator.class).loadClasses(TubingBeanAnnotationRegistrator.class)) {
                Constructor<?> declaredConstructor = aClass.getDeclaredConstructors()[0];
                TubingBeanAnnotationRegistrator tubingBeanAnnotationRegistrator = (TubingBeanAnnotationRegistrator) declaredConstructor.newInstance();
                beanAnnotations.addAll(tubingBeanAnnotationRegistrator.getAnnotations());
            }
            loadIocBeans();
        } catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("Tubing could not load the IOC container", e);
        }
    }

    public ScanResult getReflections() {
        return scanResult;
    }

    private void loadIocBeans() {
        try {
            List<Class<?>> configurationClasses = scanResult.getClassesWithAnnotation(TubingConfiguration.class).loadClasses();
            List<Method> providers = configurationClasses.stream().flatMap(c -> ReflectionUtils.getMethodsAnnotatedWith(c, IocBeanProvider.class).stream()).collect(Collectors.toList());
            List<Method> multiProviders = configurationClasses.stream().flatMap(c -> ReflectionUtils.getMethodsAnnotatedWith(c, IocMultiProvider.class).stream()).collect(Collectors.toList());

            Set<Class<?>> allBeans = new HashSet<>();
            for (Class beanAnnotation : beanAnnotations) {
                allBeans.addAll(new HashSet<>(scanResult.getClassesWithAnnotation(beanAnnotation).loadClasses()));
            }

            List<Class<?>> classesWithBeanAnnotations = allBeans.stream()
                .sorted((o1, o2) -> {
                    Annotation annotation1 = Arrays.stream(o1.getAnnotations()).filter(a -> beanAnnotations.contains(a.annotationType())).findFirst().get();
                    Annotation annotation2 = Arrays.stream(o2.getAnnotations()).filter(a -> beanAnnotations.contains(a.annotationType())).findFirst().get();
                    try {
                        boolean priority1 = (boolean) annotation1.annotationType().getMethod("priority").invoke(annotation1);
                        boolean priority2 = (boolean) annotation2.annotationType().getMethod("priority").invoke(annotation2);
                        return Boolean.compare(priority2, priority1);
                    } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                        throw new RuntimeException("Invalid bean configuration. not property found");
                    }
                })
                .collect(Collectors.toList());

            Set<Class<?>> providedBeans = providers.stream().map(Method::getReturnType).collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Class<?>> validBeans = Stream.concat(classesWithBeanAnnotations.stream(), providedBeans.stream()).collect(Collectors.toCollection(LinkedHashSet::new));

            configurationLoader = (ConfigurationLoader) instantiateBean(scanResult, ConfigurationLoader.class, validBeans, providers, multiProviders, false);
            classesWithBeanAnnotations = classesWithBeanAnnotations.stream()
                .filter(a -> iocConditionalPropertyFilter.isValidBean(beanAnnotations, a, getConfigurationFiles()))
                .collect(Collectors.toList());
            validBeans = Stream.concat(classesWithBeanAnnotations.stream(), providedBeans.stream()).collect(Collectors.toCollection(LinkedHashSet::new));

            for (Class<?> aClass : validBeans) {
                instantiateBean(scanResult, aClass, validBeans, providers, multiProviders, false);
            }

            for (Class<?> configurationClass : configurationClasses) {
                List<Method> afterMethods = ReflectionUtils.getMethodsAnnotatedWith(configurationClass, AfterIocLoad.class);
                for (Method afterMethod : afterMethods) {
                    List<Object> params = buildParams(configurationClass, scanResult, validBeans, providers, multiProviders, afterMethod.getParameterTypes(), afterMethod.getParameterAnnotations());
                    afterMethod.invoke(null, params.toArray());
                }
            }

        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IocException("Could not validate instantiate beans", e);
        }
    }

    private Object instantiateBean(ScanResult scanResult, Class<?> aClass, Set<Class<?>> validBeans, List<Method> providedBeans, List<Method> multiProviders, boolean multiProvider) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        if (multiProvider) {
            beans.putIfAbsent(aClass, new ArrayList<>());
            Set<Class<?>> subTypesOf = scanResult.getClassesImplementing(aClass).loadClasses().stream().filter(validBeans::contains).collect(Collectors.toSet());
            List list = (List) beans.get(aClass);
            for (Class<?> subClass : subTypesOf) {
                Object bean = createBean(scanResult, subClass, validBeans, providedBeans, multiProviders);
                if (!list.contains(bean)) {
                    list.add(bean);
                }
            }

            Collection<Object> beans = getMultiProvidedBeans(scanResult, aClass, validBeans, providedBeans, multiProviders);
            list.addAll(beans);

            return this.beans.get(aClass);
        }

        if (aClass.isAnnotationPresent(IocMultiProvider.class)) {
            Class[] multiClasses = aClass.getAnnotation(IocMultiProvider.class).value();
            Object bean = createBean(scanResult, aClass, validBeans, providedBeans, multiProviders);
            for (Class multiClass : multiClasses) {
                beans.putIfAbsent(multiClass, new ArrayList<>());
                List list = (List) beans.get(multiClass);
                if (!list.contains(bean) && bean != null) {
                    list.add(bean);
                }
            }
            return bean;
        } else {
            Optional<Annotation> first = Arrays.stream(aClass.getAnnotations())
                    .filter(a1 -> beanAnnotations.contains(a1.annotationType())).findFirst();
            if (first.isPresent()) {
                Annotation annotation = first.get();
                Class multiproviderClass = (Class) annotation.annotationType().getMethod("multiproviderClass").invoke(annotation);
                if (multiproviderClass != Object.class) {
                    Object bean = createBean(scanResult, aClass, validBeans, providedBeans, multiProviders);
                    beans.putIfAbsent(multiproviderClass, new ArrayList<>());
                    List list = (List) beans.get(multiproviderClass);
                    if (!list.contains(bean) && bean != null) {
                        list.add(bean);
                    }
                    return bean;
                }
            }
        }

        if (aClass.isInterface()) {
            Optional<Object> existingBean = beans.keySet().stream().filter(aClass::isAssignableFrom).map(beans::get).findFirst();
            if (existingBean.isPresent()) {
                return existingBean.get();
            }

            // Check if provider can handle it
            List<Method> currentProviders = providedBeans.stream().filter(p -> p.getReturnType() == aClass).collect(Collectors.toList());
            if (currentProviders.size() > 1) {
                throw new IocException("Multiple bean providers found for interface " + aClass.getName() + ". This is currently not supported");
            }
            if (currentProviders.size() == 1) {
                return createBean(scanResult, aClass, validBeans, providedBeans, multiProviders);
            }

            // Find only implementation of interface and instantiate
            Set<Class<?>> subTypes = scanResult.getClassesImplementing(aClass).loadClasses().stream()
                .filter(validBeans::contains)
                .collect(Collectors.toSet());

            Set<Class<?>> subtypeNonConditional = subTypes.stream()
                .filter(a -> !a.isAnnotationPresent(ConditionalOnMissingBean.class))
                .collect(Collectors.toSet());

            Set<Class<?>> subtypesOnMissing = subTypes.stream()
                .filter(a -> a.isAnnotationPresent(ConditionalOnMissingBean.class))
                .collect(Collectors.toSet());

            if (subTypes.isEmpty()) {
                throw new IocException("Cannot instantiate bean with interface " + aClass.getName() + ". No classes implementing this interface");
            }
            if (subtypeNonConditional.size() > 1) {
                throw new IocException("Multiple beans found with interface " + aClass.getName() + ". At most one bean should be defined. Use @IocMultiProvider for supporting multiple beans with one interface");
            }
            if (subtypeNonConditional.isEmpty() && subtypesOnMissing.size() > 1) {
                throw new IocException("Multiple beans found with interface " + aClass.getName() + ". At most one bean should be defined. To many beans seems to be annotated with @ConditionalOnMissingBean");
            }

            Class<?> beanToCreate = subtypeNonConditional.isEmpty() ? subtypesOnMissing.iterator().next() : subtypeNonConditional.iterator().next();
            return createBean(scanResult, beanToCreate, validBeans, providedBeans, multiProviders);
        }
        return createBean(scanResult, aClass, validBeans, providedBeans, multiProviders);
    }

    private Object createBean(ScanResult scanResult, Class<?> aClass, Set<Class<?>> validBeans, List<Method> providers, List<Method> multiProviders) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        if (beans.containsKey(aClass)) {
            return beans.get(aClass);
        }

        Optional<Method> beanProvider = providers.stream().filter(p -> p.getReturnType() == aClass).findFirst();
        if (beanProvider.isPresent()) {
            return getProvidedBean(scanResult, aClass, validBeans, providers, multiProviders);
        }

        if (Arrays.stream(aClass.getAnnotations()).noneMatch(a -> beanAnnotations.contains(a.annotationType())) && providers.stream().map(Method::getReturnType).noneMatch(a -> a == aClass)) {
            throw new IocException("Cannot instantiate bean. No Bean annotation present. [" + aClass.getName() + "]");
        }

        if (!validBeans.contains(aClass)) {
            throw new IocException("Cannot instantiate bean. No bean found for : [" + aClass + "]");
        }

        Constructor<?>[] declaredConstructors = aClass.getDeclaredConstructors();
        if (declaredConstructors.length > 1) {
            throw new IocException("Cannot instantiate bean with type " + aClass.getName() + ". Only one constructor should be defined");
        }

//        tubingPlugin.getLogger().info("[MC-IOC] Start creation of bean [" + aClass.getName() + "]");
        Constructor<?> declaredConstructor = aClass.getDeclaredConstructors()[0];
        List<Object> constructorParams = buildParams(aClass, scanResult, validBeans, providers, multiProviders, declaredConstructor.getParameterTypes(), declaredConstructor.getParameterAnnotations());

        try {
//            tubingPlugin.getLogger().info("[MC-IOC] Creating new bean [" + aClass.getName() + "] with constructor arguments [" + constructorParams.stream().map(d -> d.getClass().getName()).collect(Collectors.joining(",")) + "]");
            Object bean = declaredConstructor.newInstance(constructorParams.toArray());
            PropertyInjector.injectConfigurationProperties(bean, getConfigurationFiles());
            TubingPluginInjector.inject(bean, tubingPlugin);
            beans.putIfAbsent(aClass, bean);
            return bean;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IocException("Cannot instantiate bean with type " + aClass.getName() + ".", e);
        }
    }

    private Map<String, FileConfiguration> getConfigurationFiles() {
        if (configurationLoader == null) {
            return Collections.emptyMap();
        }
        return configurationLoader.getConfigurationFiles();
    }

    private Object getProvidedBean(ScanResult scanResult, Class<?> aClass, Set<Class<?>> validBeans, List<Method> providers, List<Method> multiProviders) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Optional<Method> beanProvider = providers.stream().filter(p -> p.getReturnType() == aClass).findFirst();
        if (beanProvider.isPresent()) {
            List<Object> params = buildParams(aClass, scanResult, validBeans, providers, multiProviders, beanProvider.get().getParameterTypes(), beanProvider.get().getParameterAnnotations());
            Object invoke = beanProvider.get().invoke(null, params.toArray());
            if (invoke != null) {
                beans.putIfAbsent(beanProvider.get().getReturnType(), invoke);
                return invoke;
            }
        }
        return null;
    }

    private Collection<Object> getMultiProvidedBeans(ScanResult scanResult, Class<?> aClass, Set<Class<?>> validBeans, List<Method> providers, List<Method> multiProviders) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Optional<Method> beanProvider = multiProviders.stream().filter(p -> {
            Class[] multiProvidedClasses = p.getAnnotation(IocMultiProvider.class).value();
            return Arrays.asList(multiProvidedClasses).contains(aClass);
        }).findFirst();

        if (beanProvider.isPresent()) {
            List<Object> params = buildParams(aClass, scanResult, validBeans, providers, multiProviders, beanProvider.get().getParameterTypes(), beanProvider.get().getParameterAnnotations());
            Collection invoke = (Collection) beanProvider.get().invoke(null, params.toArray());
            multiProviders.remove(beanProvider.get());
            return invoke == null ? Collections.emptyList() : invoke;
        }
        return Collections.emptyList();
    }

    private List<Object> buildParams(Class<?> aClass, ScanResult scanResult, Set<Class<?>> validBeans, List<Method> providedBeans, List<Method> multiProviders, Class<?>[] parameterTypes, Annotation[][] parameterAnnotations) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        List<Object> constructorParams = new ArrayList<>();

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> classParam = parameterTypes[i];
            Annotation[] annotations = parameterAnnotations[i];
            Optional<Annotation> multiAnnotation = Arrays.stream(annotations).filter(a -> a.annotationType().equals(IocMulti.class)).findFirst();

            Optional<ConfigProperty> configAnnotation = Arrays.stream(annotations)
                .filter(a -> a.annotationType().equals(ConfigProperty.class))
                .map(a -> (ConfigProperty) a).findFirst();
            Optional<InjectTubingPlugin> tubingPluginAnnotation = Arrays.stream(annotations)
                .filter(a -> a.annotationType().equals(InjectTubingPlugin.class))
                .map(a -> (InjectTubingPlugin) a).findFirst();

            if (tubingPluginAnnotation.isPresent()) {
                constructorParams.add(tubingPlugin);
            } else if (configAnnotation.isPresent()) {
                Object property = PropertyInjector.getConstructorConfigurationProperty(aClass, classParam, annotations, getConfigurationFiles());
                constructorParams.add(property);
            } else if (multiAnnotation.isPresent()) {
                IocMulti iocMulti = (IocMulti) multiAnnotation.get();
                constructorParams.add(instantiateBean(scanResult, iocMulti.value(), validBeans, providedBeans, multiProviders, true));
            } else {
                Object o = instantiateBean(scanResult, classParam, validBeans, providedBeans, multiProviders, false);
                constructorParams.add(o);
            }
        }
        return constructorParams;
    }

    public void registerBean(Object o) {
        beans.put(o.getClass(), o);
    }

    public <T> T get(Class<T> clazz) {
        if (clazz.isInterface()) {
            List<Object> collect = beans.keySet().stream().filter(clazz::isAssignableFrom).map(beans::get).collect(Collectors.toList());
            if (collect.size() > 1) {
                throw new IocException("Cannot retrieve bean with interface " + clazz.getName() + ". Too many implementations registered. Use `getList` to retrieve a list of all beans");
            }
            if (collect.isEmpty()) {
                throw new IocException("Cannot retrieve bean with interface " + clazz.getName() + ". No implementation registered");
            }
            return (T) collect.get(0);
        }
        return (T) beans.get(clazz);
    }

    public <T> List<T> getList(Class<T> clazz) {
        return (List<T>) beans.get(clazz);
    }
}
