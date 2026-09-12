#!/usr/bin/env python3
"""
Add missing imports to Java files in the identity service.

For each Java file:
1. Build a class->package map from the current source
2. Extract all class names used in the file
3. For each class that belongs to the identity service and is not imported,
   add the import.
"""

import re
from pathlib import Path

BASE = Path("/Users/vivekghosh/Documents/vivekghosh/bhukkad/backend-server/services/identity/src")

# Step 1: Build class->package map
class_to_pkg = {}
for base in [BASE / "main" / "java", BASE / "test" / "java"]:
    if not base.exists():
        continue
    for java_file in base.rglob("*.java"):
        try:
            content = java_file.read_text(encoding="utf-8")
        except Exception:
            continue
        pkg_match = re.match(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
        if not pkg_match:
            continue
        package = pkg_match.group(1)
        for match in re.finditer(
            r'(?:public\s+|private\s+|protected\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+(\w+)',
            content
        ):
            class_name = match.group(1)
            class_to_pkg[class_name] = package

print(f"Found {len(class_to_pkg)} classes")

# Step 2: For each file, find missing imports and add them
files_fixed = 0

for java_file in BASE.rglob("*.java"):
    try:
        content = java_file.read_text(encoding="utf-8")
    except Exception:
        continue

    # Extract current imports and package
    imports = set()
    for match in re.finditer(r'^import\s+([\w.]+);', content, re.MULTILINE):
        imports.add(match.group(1))

    pkg_match = re.match(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
    if not pkg_match:
        continue
    current_pkg = pkg_match.group(1)

    # Extract class names used in the file (simple heuristic)
    # Remove imports and package declaration from content first
    code_without_imports = re.sub(r'^import\s+[\w.]+;\s*', '', content, flags=re.MULTILINE)
    code_without_imports = re.sub(r'^package\s+[\w.]+;\s*', '', code_without_imports, flags=re.MULTILINE)

    # Find all potential class references
    class_refs = set()
    
    # Pattern 1: extends/implements ClassName
    for match in re.finditer(r'(?:extends|implements)\s+(\w+)', code_without_imports):
        class_refs.add(match.group(1))
    
    # Pattern 2: new ClassName
    for match in re.finditer(r'new\s+(\w+)', code_without_imports):
        class_refs.add(match.group(1))
    
    # Pattern 3: ClassName var (field/method declarations)
    for match in re.finditer(r'(?:private|protected|public|final|static|\s)\s+(\w+)\s+\w+', code_without_imports):
        class_refs.add(match.group(1))
    
    # Pattern 4: List<ClassName> or other generics
    for match in re.finditer(r'[<\s](\w+)[>\s]', code_without_imports):
        class_refs.add(match.group(1))
    
    # Pattern 5: @ClassName
    for match in re.finditer(r'@(\w+)', code_without_imports):
        class_refs.add(match.group(1))
    
    # Pattern 6: ClassName.method() or ClassName.class
    for match in re.finditer(r'(\w+)\.(?:class|method|getName|getSimpleName|valueOf|builder)\b', code_without_imports):
        class_refs.add(match.group(1))

    # Filter out common non-class identifiers
    non_classes = {
        'Override', 'FunctionalInterface', 'Test', 'TempDir', 'ExtendWith', 'Mock',
        'InjectMocks', 'Autowired', 'Qualifier', 'Value', 'Bean', 'Configuration',
        'EnableConfigurationProperties', 'Transactional', 'Slf4j', 'Data', 'Builder',
        'NoArgsConstructor', 'AllArgsConstructor', 'Getter', 'Setter', 'JsonProperty',
        'Schema', 'Table', 'Entity', 'Id', 'GeneratedValue', 'Column', 'ManyToOne',
        'OneToMany', 'JoinColumn', 'Enumerated', 'Temporal', 'Lob', 'Transient',
        'PrePersist', 'PreUpdate', 'CreatedDate', 'LastModifiedDate', 'Version',
        'AuditingEntityListener', 'Param', 'RequestBody', 'GetMapping', 'PostMapping',
        'PutMapping', 'DeleteMapping', 'PatchMapping', 'PathVariable', 'RequestParam',
        'RequestHeader', 'CookieValue', 'SessionAttribute', 'RequestAttribute',
        'MatrixVariable', 'ModelAttribute', 'CrossOrigin', 'RestController',
        'Controller', 'Service', 'Repository', 'Component', 'Configuration',
        'Primary', 'Qualifier', 'Scope', 'Lazy', 'Profile', 'Conditional',
        'Async', 'Scheduled', 'Cacheable', 'CacheEvict', 'CachePut',
        'EventListener', 'Transactional', 'Rollback', 'Commit',
        'SuppressWarnings', 'Deprecated', 'SafeVarargs', 'FunctionalInterface',
        'Native', 'Transient', 'Volatile', 'Synchronized', 'Strictfp',
        'String', 'Integer', 'Long', 'Boolean', 'Double', 'Float', 'Character',
        'Byte', 'Short', 'Object', 'Class', 'Void', 'void',
        'List', 'Map', 'Set', 'Collection', 'Queue', 'Deque', 'Stack',
        'ArrayList', 'LinkedList', 'HashMap', 'TreeMap', 'HashSet', 'TreeSet',
        'LinkedHashMap', 'LinkedHashSet', 'PriorityQueue', 'ArrayDeque',
        'Optional', 'Stream', 'IntStream', 'LongStream', 'DoubleStream',
        'Collectors', 'Comparator', 'Comparable', 'Iterator', 'ListIterator',
        'Iterable', 'Spliterator', 'Predicate', 'Function', 'Consumer',
        'Supplier', 'BiFunction', 'BiConsumer', 'BiPredicate',
        'Runnable', 'Callable', 'Future', 'CompletableFuture',
        'LocalDate', 'LocalDateTime', 'LocalTime', 'ZonedDateTime',
        'Instant', 'Date', 'Calendar', 'TimeZone', 'ZoneId',
        'BigDecimal', 'BigInteger', 'UUID', 'Random',
        'File', 'Path', 'Files', 'Paths', 'URI', 'URL',
        'Pattern', 'Matcher', 'StringBuilder', 'StringBuffer',
        'System', 'Math', 'Runtime', 'ProcessBuilder',
        'HttpStatus', 'MediaType', 'HttpHeaders', 'HttpEntity',
        'ResponseEntity', 'RestTemplate', 'WebClient',
        'JpaRepository', 'CrudRepository', 'PagingAndSortingRepository',
        'JpaSpecificationExecutor', 'QueryByExampleExecutor',
        'Pageable', 'Page', 'Sort', 'Direction', 'Slice',
        'JdbcTemplate', 'NamedParameterJdbcTemplate',
        'EntityManager', 'EntityTransaction', 'Query', 'TypedQuery',
        'CriteriaBuilder', 'CriteriaQuery', 'Predicate', 'Root',
        'ParameterExpression', 'Expression', 'Selection', 'Fetch',
        'Join', 'Subquery', 'Tuple', 'Metamodel',
        'Lazy', 'Eager', 'FetchType', 'Cascade', 'OrphanRemoval',
        'NotNull', 'NotBlank', 'Size', 'Email', 'Pattern', 'Min', 'Max',
        'Valid', 'DecimalMin', 'DecimalMax', 'Digits', 'Past', 'Future',
        'AssertTrue', 'AssertFalse', 'Null', 'NotEmpty', 'Positive',
        'PositiveOrZero', 'Negative', 'NegativeOrZero',
        'JsonIgnore', 'JsonProperty', 'JsonFormat', 'JsonDeserialize',
        'JsonSerialize', 'JsonCreator', 'JsonValue',
        'EqualsAndHashCode', 'ToString', 'Equals', 'HashCode',
        'With', 'Builder', 'SuperBuilder', 'Wither',
        'UtilityClass', 'Experimental', 'Beta', 'Alpha',
    }
    
    class_refs -= non_classes
    
    missing_imports = []
    for cls in class_refs:
        if cls in class_to_pkg:
            new_pkg = class_to_pkg[cls]
            if new_pkg != current_pkg:
                import_line = f"import {new_pkg}.{cls};"
                if import_line not in imports:
                    missing_imports.append(import_line)
    
    if missing_imports:
        # Add imports after the package declaration
        lines = content.split('\n')
        new_lines = []
        added = False
        for i, line in enumerate(lines):
            new_lines.append(line)
            if not added and line.startswith('package '):
                # Add blank line and imports after package
                new_lines.append('')
                for imp in sorted(missing_imports):
                    new_lines.append(imp)
                added = True
        new_content = '\n'.join(new_lines)
        if new_content != content:
            java_file.write_text(new_content)
            files_fixed += 1
            print(f"Fixed {java_file.relative_to(BASE.parent)}: added {len(missing_imports)} imports")

print(f"\nTotal files with added imports: {files_fixed}")
