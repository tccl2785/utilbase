package io.github.tccl2785.tools.load.yaml

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object YamlAssignUtils {

    /**
     * 核心入口函数
     *
     * @param yamlStr YAML 格式字符串
     * @param targetObject Kotlin 的 object 单例对象 或 普通类实例
     */
    fun parseAssignFields(yamlStr: String, targetObject: Any) {
        if (yamlStr.isBlank()) return

        val yamlMap = parseYamlToNodeMap(yamlStr)

        assignYamlMapToObject(
            yamlMap = yamlMap,
            targetObject = targetObject
        )
    }

    /**
     * 核心入口函数，支持多个目标对象
     *
     * @param yamlStr YAML 格式字符串
     * @param targetObjects Kotlin 的 object 单例对象 或 普通类实例数组
     */
    fun parseAssignFields(yamlStr: String, targetObjects: Array<Any>) {
        if (yamlStr.isBlank()) return

        val yamlMap = parseYamlToNodeMap(yamlStr)

        for (targetObject in targetObjects) {
            assignYamlMapToObject(
                yamlMap = yamlMap,
                targetObject = targetObject
            )
        }
    }

    /**
     * 将 YAML 字符串解析成顶层 Map<String, Node>
     */
    private fun parseYamlToNodeMap(yamlStr: String): Map<String, Node> {
        val loaderOptions = LoaderOptions()
        val yaml = Yaml(loaderOptions)

        val rootNode = yaml.compose(StringReader(yamlStr))

        if (rootNode !is MappingNode) {
            throw IllegalArgumentException("YAML 顶层结构必须是 Map 结构 (MappingNode)")
        }

        return rootNode.value.associate {
            val keyNode = it.keyNode

            if (keyNode !is ScalarNode) {
                throw IllegalArgumentException("YAML 顶层 Key 必须是字符串")
            }

            keyNode.value to it.valueNode
        }
    }

    /**
     * 把顶层 YAML Map 赋值给目标对象
     *
     * 例如：
     *
     * YAML:
     * db_config: !org.apache.commons.dbcp2.BasicDataSource
     *
     * 目标：
     * object BaseConfig {
     *     var db_config: BasicDataSource = BasicDataSource()
     * }
     */
    private fun assignYamlMapToObject(
        yamlMap: Map<String, Node>,
        targetObject: Any
    ) {
        val targetClass = targetObject.javaClass
        val fields = getAllFields(targetClass)

        for (field in fields) {
            field.isAccessible = true

            val fieldName = field.name

            // 忽略 Kotlin object 单例内部字段，以及编译器生成的字段
            if (fieldName == "INSTANCE" || fieldName.startsWith("$")) continue

            val node = yamlMap[fieldName] ?: continue

            try {
                val value = parseNodeToField(
                    node = node,
                    targetType = field.type,
                    genericType = field.genericType,
                    currentPath = fieldName
                )

                field.set(targetObject, value)

            } catch (e: Exception) {
                if (e is YamlAssignException) throw e

                throw YamlAssignException(
                    fieldName = fieldName,
                    fieldType = field.type.canonicalName,
                    yamlNodeType = node::class.java.simpleName,
                    yamlTag = node.tag.value,
                    cause = e
                )
            }
        }
    }

    /**
     * 递归解析 Node 节点并转为目标类型的值
     */
    private fun parseNodeToField(
        node: Node,
        targetType: Class<*>,
        genericType: Type?,
        currentPath: String
    ): Any? {
        return when (node) {
            is ScalarNode -> {
                castScalarValue(
                    value = node.value,
                    targetType = targetType
                )
            }

            is MappingNode -> {
                createAndFillObject(
                    node = node,
                    defaultType = targetType,
                    currentPath = currentPath
                )
            }

            is SequenceNode -> {
                createCollection(
                    node = node,
                    genericType = genericType,
                    currentPath = currentPath
                )
            }

            else -> null
        }
    }

    /**
     * 解析单个对象：
     *
     * 关键修改点：
     *
     * 旧逻辑：
     * 1. 遍历目标类字段
     * 2. field.set(instance, value)
     *
     * 新逻辑：
     * 1. 遍历 YAML 里面写的 key
     * 2. 优先调用 setter
     * 3. 找不到 setter，再降级 field.set()
     *
     * 这样 BasicDataSource 这种 JavaBean 对象才能正确赋值：
     *
     * driverClassName -> setDriverClassName(...)
     * url             -> setUrl(...)
     * username        -> setUsername(...)
     * password        -> setPassword(...)
     */
    private fun createAndFillObject(
        node: MappingNode,
        defaultType: Class<*>,
        currentPath: String
    ): Any {
        val tagValue = node.tag.value

        val targetClass = if (tagValue.startsWith("!")) {
            val className = tagValue.substring(1)

            try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException("无法根据 YAML 头信息加载类: [$className]", e)
            }
        } else {
            defaultType
        }

        val instance = try {
            val constructor = targetClass.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (e: Exception) {
            throw IllegalStateException(
                "无法实例化类 [${targetClass.name}]，请确保该类包含无参构造函数",
                e
            )
        }

        /**
         * 重点：
         * 这里必须遍历 YAML 的属性，而不是遍历 Java/Kotlin 类的字段。
         */
        for (tuple in node.value) {
            val keyNode = tuple.keyNode
            val valueNode = tuple.valueNode

            if (keyNode !is ScalarNode) {
                throw IllegalArgumentException("对象 [$currentPath] 的属性名必须是字符串")
            }

            val propertyName = keyNode.value
            val propertyPath = "$currentPath.$propertyName"

            try {
                assignProperty(
                    instance = instance,
                    targetClass = targetClass,
                    propertyName = propertyName,
                    node = valueNode,
                    currentPath = propertyPath
                )
            } catch (e: Exception) {
                if (e is YamlAssignException) throw e

                throw YamlAssignException(
                    fieldName = propertyPath,
                    fieldType = targetClass.canonicalName,
                    yamlNodeType = valueNode::class.java.simpleName,
                    yamlTag = valueNode.tag.value,
                    cause = e
                )
            }
        }

        return instance
    }

    /**
     * 给对象的某个属性赋值。
     *
     * 优先级：
     *
     * 1. JavaBean setter
     * 2. 同名字段 field
     */
    private fun assignProperty(
        instance: Any,
        targetClass: Class<*>,
        propertyName: String,
        node: Node,
        currentPath: String
    ) {
        /**
         * 1. 优先找 setter
         *
         * 例如：
         *
         * driverClassName -> setDriverClassName(...)
         * url             -> setUrl(...)
         * username        -> setUsername(...)
         * password        -> setPassword(...)
         */
        val setter = findSetter(
            clazz = targetClass,
            propertyName = propertyName
        )

        if (setter != null) {
            setter.isAccessible = true

            val paramType = setter.parameterTypes[0]
            val genericParamType = setter.genericParameterTypes[0]

            val value = parseNodeToField(
                node = node,
                targetType = paramType,
                genericType = genericParamType,
                currentPath = currentPath
            )

            setter.invoke(instance, value)
            return
        }

        /**
         * 2. 没有 setter，再降级使用字段反射
         */
        val field = findField(
            clazz = targetClass,
            fieldName = propertyName
        ) ?: throw IllegalArgumentException(
            "类 [${targetClass.name}] 中找不到属性 [$propertyName]，既没有 setter，也没有同名字段"
        )

        field.isAccessible = true

        val value = parseNodeToField(
            node = node,
            targetType = field.type,
            genericType = field.genericType,
            currentPath = currentPath
        )

        field.set(instance, value)
    }

    /**
     * 查找 JavaBean setter
     *
     * propertyName = url
     * setterName   = setUrl
     *
     * propertyName = driverClassName
     * setterName   = setDriverClassName
     */
    private fun findSetter(
        clazz: Class<*>,
        propertyName: String
    ): Method? {
        if (propertyName.isBlank()) return null

        val setterName = "set" + propertyName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        /**
         * clazz.methods：
         * 包含 public 方法，也包含父类 public 方法。
         *
         * 对 BasicDataSource 很重要。
         */
        return clazz.methods.firstOrNull {
            it.name == setterName && it.parameterCount == 1
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == setterName && it.parameterCount == 1
        }
    }

    /**
     * 查找字段，包括父类字段
     */
    private fun findField(
        clazz: Class<*>,
        fieldName: String
    ): Field? {
        var current: Class<*>? = clazz

        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }

        return null
    }

    /**
     * 解析集合类型：
     *
     * 1. 优先使用【集合节点本身的 Tag !头信息】
     * 2. 降级检查【每个元素自身的 Tag !头信息】
     * 3. 降级使用【List<T> 的泛型类型】
     */
    private fun createCollection(
        node: SequenceNode,
        genericType: Type?,
        currentPath: String
    ): List<Any?> {
        val list = mutableListOf<Any?>()

        val sequenceTag = node.tag.value

        /**
         * 1. 尝试解析集合本身的 !头信息
         *
         * 例如：
         *
         * readDb: !org.apache.commons.dbcp2.BasicDataSource
         *   - ...
         *   - ...
         */
        val collectionElementClass: Class<*>? = if (sequenceTag.startsWith("!")) {
            val className = sequenceTag.substring(1)

            try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException(
                    "对象集合 [$currentPath] 无法根据其头信息加载元素类: [$className]",
                    e
                )
            }
        } else {
            null
        }

        /**
         * 2. 提取 List<T> 的泛型 Class
         */
        val genericClass = if (genericType is ParameterizedType) {
            genericType.actualTypeArguments.getOrNull(0) as? Class<*>
        } else {
            null
        }

        /**
         * 3. 遍历集合元素
         */
        for ((index, itemNode) in node.value.withIndex()) {
            val itemPath = "$currentPath[$index]"
            val itemTag = itemNode.tag.value

            val targetElementClass: Class<*> = when {
                /**
                 * 优先级 1：
                 * 集合节点身上有 !类名
                 */
                collectionElementClass != null -> {
                    collectionElementClass
                }

                /**
                 * 优先级 2：
                 * 元素自己身上有 !类名
                 *
                 * 例如：
                 *
                 * readDb:
                 *   - !org.apache.commons.dbcp2.BasicDataSource
                 *     url: xxx
                 */
                itemTag.startsWith("!") -> {
                    val className = itemTag.substring(1)

                    try {
                        Class.forName(className)
                    } catch (e: ClassNotFoundException) {
                        throw IllegalArgumentException(
                            "集合元素 [$itemPath] 无法根据其自身头信息加载类: [$className]",
                            e
                        )
                    }
                }

                /**
                 * 优先级 3：
                 * 使用 List<T> 的泛型类型
                 */
                genericClass != null -> {
                    genericClass
                }

                else -> {
                    throw IllegalArgumentException(
                        "集合元素 [$itemPath] 缺少 !头信息 且 集合自身无头信息，无法判断元素类型"
                    )
                }
            }

            val elementObj = if (itemNode is MappingNode) {
                createAndFillObject(
                    node = itemNode,
                    defaultType = targetElementClass,
                    currentPath = itemPath
                )
            } else {
                parseNodeToField(
                    node = itemNode,
                    targetType = targetElementClass,
                    genericType = null,
                    currentPath = itemPath
                )
            }

            list.add(elementObj)
        }

        return list
    }

    /**
     * 标量类型转换
     */
    private fun castScalarValue(
        value: String,
        targetType: Class<*>
    ): Any? {
        return when (targetType) {
            String::class.java -> value

            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType -> value.toBoolean()

            Int::class.javaPrimitiveType,
            Int::class.javaObjectType -> value.toInt()

            Long::class.javaPrimitiveType,
            Long::class.javaObjectType -> value.toLong()

            Double::class.javaPrimitiveType,
            Double::class.javaObjectType -> value.toDouble()

            Float::class.javaPrimitiveType,
            Float::class.javaObjectType -> value.toFloat()

            Short::class.javaPrimitiveType,
            Short::class.javaObjectType -> value.toShort()

            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType -> value.toByte()

            Char::class.javaPrimitiveType,
            Char::class.javaObjectType -> value.firstOrNull()

            else -> value
        }
    }

    /**
     * 获取当前类及父类的所有字段
     */
    private fun getAllFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()

        var current: Class<*>? = clazz

        while (current != null && current != Any::class.java) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }

        return fields
    }

    class YamlAssignException(
        fieldName: String,
        fieldType: String?,
        yamlNodeType: String,
        yamlTag: String,
        cause: Throwable
    ) : RuntimeException(
        """
        |
        |❌ [YAML 解析赋值中断] 发生错误！
        |------------------------------------------------------------------
        |► 出错字段/路径 (Field Path) : $fieldName
        |► 目标字段类型 (Field Type) : $fieldType
        |► YAML 对应的 Node 结构    : $yamlNodeType
        |► YAML 头信息 (Tag !后内容) : $yamlTag
        |► 详细报错原因 (Error Detail): ${cause.message}
        |------------------------------------------------------------------
        """.trimMargin(),
        cause
    )
}