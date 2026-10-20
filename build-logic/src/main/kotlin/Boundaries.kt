/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import java.io.Serializable

/**
 * The module boundaries declared in `config/boundaries.toml` (docs/decisions/0044): for each module
 * of the build, the modules it may depend on, and what its compiled classes may refer to.
 */
class Boundaries
private constructor(
    private val modules: Map<String, Module>,
    private val restrictedMembers: List<RestrictedMembers>,
    private val restrictedClasses: List<RestrictedClasses>,
    private val reservedPackages: List<ReservedPackage>,
    private val bootstrapMethods: List<MemberPattern>,
    private val baseline: Allowlist,
) {
    private class Module(val allowedDependencies: Set<String>, val classes: ClassCheck)

    /** Whether the classes of a module are checked (`check_classes`). */
    private sealed interface ClassCheck {
        /** Against the rules, and the baseline with [allowlist] (`allowed_references`). */
        class Checked(val allowlist: Allowlist) : ClassCheck

        /** Not at all: an app, which may use any capability. */
        data object Unchecked : ClassCheck
    }

    /**
     * The classes outside the project that classes may refer to, with all their members: the
     * [classes] named (not their nested classes) and those directly in [packages] (not in their
     * subpackages). [types] may only be named as types, and [members] only used: the owner of a
     * member, and its subtypes, may be named as types too.
     */
    private class Allowlist(
        val classes: Set<String>,
        val packages: Set<String>,
        val types: Set<String>,
        val members: List<MemberPattern>,
    ) {
        fun allowsClass(name: String): Boolean =
            name in classes || name.substringBeforeLast('/', missingDelimiterValue = "") in packages

        fun allowsType(name: String, hierarchy: ClassHierarchy): Boolean =
            allowsClass(name) ||
                name in types ||
                members.any { hierarchy.isSubtype(name, it.owner) }

        fun allowsMember(member: MemberReference, hierarchy: ClassHierarchy): Boolean =
            allowsClass(member.owner) || members.any { it.matches(member, hierarchy) }

        operator fun plus(other: Allowlist): Allowlist =
            Allowlist(
                classes + other.classes,
                packages + other.packages,
                types + other.types,
                members + other.members,
            )
    }

    /**
     * Classes and members that only [allowedModules] may refer to at all: the classes named in
     * [classes] (with their nested classes), those in [packages] (with their subpackages), and the
     * [members] used through any subtype of their owners.
     */
    private class RestrictedClasses(
        val classes: Set<String>,
        val packages: Set<String>,
        val members: List<MemberPattern>,
        val reason: String,
        val allowedModules: Set<String>,
    ) {
        fun containsClass(name: String): Boolean =
            classes.any { name == it || name.startsWith("$it$") } ||
                packages.any { name.startsWith("$it/") }
    }

    /** A package, with its subpackages, in which only [modules] may declare classes. */
    private class ReservedPackage(
        val packageName: String,
        val modules: Set<String>,
        val reason: String,
    )

    /**
     * Members named `owner.name`, of any descriptor, or `owner.name(...` for the descriptors that
     * start with what follows the name.
     */
    private class MemberPattern(val owner: String, val name: String, val descriptorPrefix: String) {
        fun matches(member: MemberReference, hierarchy: ClassHierarchy): Boolean =
            member.name == name &&
                member.descriptor.startsWith(descriptorPrefix) &&
                hierarchy.isSubtype(member.owner, owner)

        override fun toString(): String = "$owner.$name$descriptorPrefix"

        companion object {
            fun parse(text: String, where: String): MemberPattern {
                val owner = text.substringBefore('.', missingDelimiterValue = "")
                val rest = text.substringAfter('.')
                val name = rest.substringBefore('(')
                require(owner.isNotEmpty() && name.isNotEmpty()) {
                    "$where: '$text' is not owner.name or owner.name(descriptor"
                }
                return MemberPattern(owner, name, rest.removePrefix(name))
            }
        }
    }

    /**
     * Classes whose fields and methods only [allowedModules] may use. Other modules may still name
     * the classes as types, for example to pass an instance on.
     */
    private class RestrictedMembers(
        val classes: Set<String>,
        val reason: String,
        val allowedModules: Set<String>,
    )

    /**
     * The violations of a build whose projects have [projectPaths]: every project must be declared,
     * and every declared module must be a project.
     */
    fun moduleViolations(projectPaths: Set<String>): List<String> {
        val projects = projectPaths.map { it.removePrefix(":") }.toSet()
        val undeclared =
            (projects - modules.keys).sorted().map {
                "project ':$it' is not declared in config/boundaries.toml"
            }
        val missing =
            (modules.keys - projects).sorted().map {
                "module '$it' of config/boundaries.toml is not a project of the build"
            }
        return undeclared + missing
    }

    /**
     * The violations among [edges]: an edge to a project of this build must come from a declared
     * module that may depend on it, or from the project itself (such as the tests of a module
     * depending on its own test fixtures). An edge from a module of a repository to a project, or
     * to a project of another build, is a violation however it came about (for example a dependency
     * substitution), since it puts code on the class path that the declared edges do not show.
     */
    fun dependencyViolations(edges: List<DependencyEdge>): List<String> = edges.mapNotNull { edge ->
        violationOf(edge)?.let { "${edge.configuration}: $it" }
    }

    private fun violationOf(edge: DependencyEdge): String? {
        val to =
            when (val node = edge.to) {
                is DependencyNode.Project -> node
                is DependencyNode.OtherBuild ->
                    return "${edge.from} depends on $node, which is not part of this build"
                is DependencyNode.External -> return null
            }
        val from =
            when (val node = edge.from) {
                is DependencyNode.Project -> node
                is DependencyNode.OtherBuild,
                is DependencyNode.External -> return "$node resolves to $to"
            }
        if (from == to) return null
        val allowed =
            modules[from.moduleName]?.allowedDependencies
                ?: return "$from is not declared in config/boundaries.toml"
        if (to.moduleName in allowed) return null
        return "'${from.moduleName}' must not depend on '${to.moduleName}'"
    }

    /**
     * The violations of the restrictions in [classes], the main classes of [module], whose
     * supertypes [hierarchy] tells: references to restricted classes and members, classes declared
     * in reserved packages, and bootstrap methods not listed. None if the module's classes are not
     * checked (`check_classes = false`).
     */
    fun classViolations(
        module: String,
        classes: List<ClassReferences>,
        hierarchy: ClassHierarchy,
    ): List<String> {
        when (module(module).classes) {
            is ClassCheck.Checked -> Unit
            ClassCheck.Unchecked -> return emptyList()
        }
        val memberRules = restrictedMembers.filter { module !in it.allowedModules }
        val classRules = restrictedClasses.filter { module !in it.allowedModules }
        val packages = reservedPackages.filter { module !in it.modules }
        return classes.flatMap { references ->
            val name = references.name
            val members = references.members.sortedBy { it.toString() }
            packages
                .filter { name.startsWith("${it.packageName}/") }
                .map {
                    "$name is in ${it.packageName}, which only ${it.modules} may declare: ${it.reason}"
                } +
                references.types.sorted().flatMap { type ->
                    classRules
                        .filter { it.containsClass(type) }
                        .map { "$name refers to $type: ${it.reason}" }
                } +
                members.flatMap { member ->
                    memberRules
                        .filter { rule ->
                            rule.classes.any { hierarchy.isSubtype(member.owner, it) }
                        }
                        .map { "$name uses $member: ${it.reason}" } +
                        classRules
                            .filter { rule -> rule.members.any { it.matches(member, hierarchy) } }
                            .map { "$name uses $member: ${it.reason}" }
                } +
                references.bootstraps
                    .filter { bootstrap ->
                        bootstrapMethods.none { it.matches(bootstrap, hierarchy) }
                    }
                    .sortedBy { it.toString() }
                    .map {
                        "$name uses the bootstrap method $it, which bootstrap_methods does not list"
                    }
        }
    }

    /**
     * The references in [classes], the main classes of [module], to classes outside the project
     * that the baseline and the allowlist of the module do not allow. [projectClasses] are the
     * classes of the modules that [module] depends on, which only [classViolations] restricts. None
     * if the module's classes are not checked, or it has no allowlist.
     */
    fun allowlistViolations(
        module: String,
        classes: List<ClassReferences>,
        projectClasses: Set<String>,
        hierarchy: ClassHierarchy,
    ): List<String> {
        val allowlist =
            when (val classCheck = module(module).classes) {
                is ClassCheck.Checked -> baseline + classCheck.allowlist
                ClassCheck.Unchecked -> return emptyList()
            }
        val internal = projectClasses + classes.map { it.name }
        return classes.flatMap { references ->
            val name = references.name
            references.types
                .filter { it !in internal && !allowlist.allowsType(it, hierarchy) }
                .sorted()
                .map { "$name refers to $it, which the allowlist of $module does not allow" } +
                references.members
                    .mapNotNull { externalMember(it, internal, hierarchy) }
                    .filter { !allowsMember(it, allowlist, hierarchy) }
                    .distinct()
                    .sortedBy { it.toString() }
                    .map { "$name uses $it, which the allowlist of $module does not allow" }
        }
    }

    private fun module(name: String): Module =
        requireNotNull(modules[name]) { "module '$name' is not declared in config/boundaries.toml" }

    /**
     * Whether [allowlist] allows [member], a member outside the project: by its owner, by a member
     * entry, or by the class that declares it, so that the members a listed class declares, such as
     * those of `Object`, may be used through any class.
     */
    private fun allowsMember(
        member: MemberReference,
        allowlist: Allowlist,
        hierarchy: ClassHierarchy,
    ): Boolean =
        allowlist.allowsMember(member, hierarchy) ||
            allowlist.allowsClass(
                hierarchy.declaringClass(member.owner, member.name, member.descriptor)
            )

    /**
     * The member outside the project that a use of [member] reaches, or null if it is a member of
     * the project. A use through a class of the project reaches a member that the class may inherit
     * from a class outside it, which is then what an allowlist must allow.
     */
    private fun externalMember(
        member: MemberReference,
        internal: Set<String>,
        hierarchy: ClassHierarchy,
    ): MemberReference? {
        if (member.owner !in internal) return member
        val declaring = hierarchy.declaringClass(member.owner, member.name, member.descriptor)
        return if (declaring in internal) null else member.copy(owner = declaring)
    }

    companion object {
        /** Reads the text of `config/boundaries.toml`. Fails on anything it does not understand. */
        fun parse(text: String): Boundaries {
            val root = TomlMapper().readTree(text)
            root.requireKeys(TOP_LEVEL_KEYS, "the top level")
            val modulesNode = root.requireField("modules", "the top level")
            require(modulesNode.isObject) { "modules must be a table" }
            val modules =
                modulesNode.properties().associate { (name, module) ->
                    val where = "modules.$name"
                    require(module.isObject) { "$where must be a table" }
                    module.requireKeys(MODULE_KEYS, where)
                    name to
                        Module(
                            module
                                .requireField("allowed_dependencies", where)
                                .strings("$where.allowed_dependencies"),
                            classCheck(module, where),
                        )
                }
            for ((name, module) in modules) {
                module.allowedDependencies.requireModules(
                    modules,
                    "modules.$name.allowed_dependencies",
                )
            }
            val restrictedMembers =
                root.optionalArray("restricted_members").mapIndexed { index, rule ->
                    val where = "restricted_members[$index]"
                    require(rule.isObject) { "$where must be a table" }
                    rule.requireKeys(RESTRICTED_MEMBERS_KEYS, where)
                    RestrictedMembers(
                        rule.requireField("classes", where).strings("$where.classes"),
                        rule.requireField("reason", where).text("$where.reason"),
                        rule
                            .requireField("allowed_modules", where)
                            .strings("$where.allowed_modules")
                            .also { it.requireModules(modules, "$where.allowed_modules") },
                    )
                }
            val restrictedClasses =
                root.optionalArray("restricted_classes").mapIndexed { index, rule ->
                    val where = "restricted_classes[$index]"
                    require(rule.isObject) { "$where must be a table" }
                    rule.requireKeys(RESTRICTED_CLASSES_KEYS, where)
                    RestrictedClasses(
                            rule.optionalStrings("classes", where),
                            rule.optionalStrings("packages", where),
                            rule.optionalStrings("members", where).map {
                                MemberPattern.parse(it, "$where.members")
                            },
                            rule.requireField("reason", where).text("$where.reason"),
                            rule
                                .requireField("allowed_modules", where)
                                .strings("$where.allowed_modules")
                                .also { it.requireModules(modules, "$where.allowed_modules") },
                        )
                        .also {
                            require(
                                it.classes.isNotEmpty() ||
                                    it.packages.isNotEmpty() ||
                                    it.members.isNotEmpty()
                            ) {
                                "$where names no classes, packages, or members"
                            }
                        }
                }
            val reservedPackages =
                root.optionalArray("reserved_packages").mapIndexed { index, rule ->
                    val where = "reserved_packages[$index]"
                    require(rule.isObject) { "$where must be a table" }
                    rule.requireKeys(RESERVED_PACKAGES_KEYS, where)
                    ReservedPackage(
                        rule.requireField("package", where).text("$where.package"),
                        rule.requireField("modules", where).strings("$where.modules").also {
                            it.requireModules(modules, "$where.modules")
                        },
                        rule.requireField("reason", where).text("$where.reason"),
                    )
                }
            val bootstrapMethods =
                root
                    .requireField("bootstrap_methods", "the top level")
                    .strings("bootstrap_methods")
                    .map { MemberPattern.parse(it, "bootstrap_methods") }
            val baseline =
                allowlist(
                    root.requireField("baseline_references", "the top level"),
                    "baseline_references",
                )
            return Boundaries(
                modules,
                restrictedMembers,
                restrictedClasses,
                reservedPackages,
                bootstrapMethods,
                baseline,
            )
        }

        /** A module whose classes are checked must have an allowlist, and only such a module. */
        private fun classCheck(module: JsonNode, where: String): ClassCheck {
            val checked =
                module.requireField("check_classes", where).boolean("$where.check_classes")
            val allowlist = module.get("allowed_references")
            if (!checked) {
                require(allowlist == null) {
                    "$where has allowed_references, but its classes are not checked"
                }
                return ClassCheck.Unchecked
            }
            return ClassCheck.Checked(
                allowlist(
                    requireNotNull(allowlist) { "$where has no allowed_references" },
                    "$where.allowed_references",
                )
            )
        }

        private fun allowlist(node: JsonNode, where: String): Allowlist {
            require(node.isObject) { "$where must be a table" }
            node.requireKeys(ALLOWLIST_KEYS, where)
            return Allowlist(
                node.requireField("classes", where).strings("$where.classes"),
                node.requireField("packages", where).strings("$where.packages"),
                node.requireField("types", where).strings("$where.types"),
                node.requireField("members", where).strings("$where.members").map {
                    MemberPattern.parse(it, "$where.members")
                },
            )
        }

        private val TOP_LEVEL_KEYS =
            setOf(
                "modules",
                "restricted_members",
                "restricted_classes",
                "reserved_packages",
                "bootstrap_methods",
                "baseline_references",
            )
        private val ALLOWLIST_KEYS = setOf("classes", "packages", "types", "members")
        private val RESTRICTED_CLASSES_KEYS =
            setOf("classes", "packages", "members", "reason", "allowed_modules")
        private val RESERVED_PACKAGES_KEYS = setOf("package", "modules", "reason")
        private val MODULE_KEYS =
            setOf("allowed_dependencies", "check_classes", "allowed_references")
        private val RESTRICTED_MEMBERS_KEYS = setOf("classes", "reason", "allowed_modules")

        private fun JsonNode.requireKeys(known: Set<String>, where: String) {
            val unknown = fieldNames().asSequence().toSet() - known
            require(unknown.isEmpty()) { "unknown keys in $where: $unknown" }
        }

        private fun JsonNode.requireField(name: String, where: String): JsonNode =
            requireNotNull(get(name)) { "$where has no $name" }

        private fun JsonNode.optionalArray(name: String): List<JsonNode> {
            val node = get(name) ?: return emptyList()
            require(node.isArray) { "$name must be an array of tables" }
            return node.toList()
        }

        private fun JsonNode.optionalStrings(name: String, where: String): Set<String> {
            val node = get(name) ?: return emptySet()
            return node.strings("$where.$name")
        }

        private fun JsonNode.strings(where: String): Set<String> {
            require(isArray) { "$where must be an array" }
            return map { it.text(where) }.toSet()
        }

        private fun JsonNode.text(where: String): String {
            require(isTextual) { "$where must hold strings only" }
            return textValue()
        }

        private fun JsonNode.boolean(where: String): Boolean {
            require(isBoolean) { "$where must be true or false" }
            return booleanValue()
        }

        private fun Set<String>.requireModules(modules: Map<String, Module>, where: String) {
            val unknown = this - modules.keys
            require(unknown.isEmpty()) { "$where names undeclared modules: $unknown" }
        }
    }
}

/** A component of a dependency graph, as the boundary check sees it. */
sealed interface DependencyNode : Serializable {
    /** A project of this build, by its path (e.g. `:vtfx-core`). */
    data class Project(val path: String) : DependencyNode {
        /** The name of the module in `config/boundaries.toml`. */
        val moduleName: String
            get() = path.removePrefix(":")

        override fun toString(): String = "project '$path'"
    }

    /** A project of another build, such as an included build. */
    data class OtherBuild(val buildPath: String, val projectPath: String) : DependencyNode {
        override fun toString(): String = "project '$projectPath' of build '$buildPath'"
    }

    /** Anything that is not a project: a module of a repository, or files. */
    data class External(val name: String) : DependencyNode {
        override fun toString(): String = "'$name'"
    }
}

/** A dependency from [from] on [to], declared in or resolved for [configuration]. */
data class DependencyEdge(
    val configuration: String,
    val from: DependencyNode,
    val to: DependencyNode,
) : Serializable
