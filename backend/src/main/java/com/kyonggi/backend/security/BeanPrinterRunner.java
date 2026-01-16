package com.kyonggi.backend.security;

import java.util.Arrays;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BeanPrinterRunner implements CommandLineRunner {

    private static final String BASE = "com.kyonggi.backend.";

    private final ApplicationContext applicationContext;

    @Override
    public void run(String... args) {
        log.info("=============================================");
        log.info("       Registered Beans (Grouped)            ");
        log.info("=============================================");

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        Arrays.sort(beanNames);

        // groupKey -> sorted classNames (중복 제거 + 정렬)
        Map<String, SortedSet<String>> grouped = new TreeMap<>();

        for (String beanName : beanNames) {
            Class<?> type = applicationContext.getType(beanName);
            if (type == null) continue;

            String className = type.getName();
            if (!className.startsWith("com.kyonggi")) continue;

            String groupKey = groupKeyOf(className);
            grouped.computeIfAbsent(groupKey, k -> new TreeSet<>())
                   .add(className);
        }

        grouped.forEach((group, classes) -> {
            log.info("----- {}", group);
            classes.forEach(clazz -> log.info("{}", clazz));
        });

        log.info("=============================================");
    }

    private static String groupKeyOf(String className) {
        if (!className.startsWith(BASE)) return "(other)";

        String rest = className.substring(BASE.length());
        int dot = rest.indexOf('.');
        if (dot < 0) return rest;
        return rest.substring(0, dot); // auth / security / global ...
    }
}
