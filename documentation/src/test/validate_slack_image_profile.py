#!/usr/bin/env python3
# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0.
"""Offline XML regression checks; run with python3 from any working directory."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
NS = {"m": "http://maven.apache.org/POM/4.1.0"}
POM = ROOT / "extensions/bot-parent/slack-bot/pom.xml"
CLI = "io.yupiik.fusion.framework.api.main.CliLauncher"
ROLES = {
    "controller": (CLI, "10001:10001", "yca-slack-controller:local"),
    "agent": (CLI, "10001:10001", "yca-slack-agent:local"),
    "services": ("io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway",
                 "10001:10001", "yca-services:local"),
    "egress": ("io.yupiik.ia.agent.github.execution.EgressGateway",
               "65532:65532", "yca-egress:local"),
}


def text(node, path):
    return node.findtext(path, namespaces=NS)


def plugins(node):
    return node.findall("m:build/m:plugins/m:plugin", NS)


class SlackImageProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pom = ET.parse(POM).getroot()
        cls.profiles = {
            text(profile, "m:id"): profile
            for profile in cls.pom.findall("m:profiles/m:profile", NS)
        }
        cls.profile = cls.profiles["image-all"]
        cls.jib = next(plugin for plugin in plugins(cls.profile)
                       if text(plugin, "m:artifactId") == "jib-maven-plugin")
        parent = ET.parse(POM.parent.parent / "pom.xml").getroot()
        cls.managed = next(plugin for plugin in parent.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
                           if text(plugin, "m:artifactId") == "jib-maven-plugin")

    def test_four_independent_package_executions(self):
        self.assertIsNone(self.jib.find("m:configuration", NS))
        executions = self.jib.findall("m:executions/m:execution", NS)
        self.assertEqual({role + "-image" for role in ROLES},
                         {text(execution, "m:id") for execution in executions})
        self.assertEqual(4, len(executions))
        outputs = set()
        for execution in executions:
            role = text(execution, "m:id").removesuffix("-image")
            main, user, image = ROLES[role]
            with self.subTest(role=role):
                self.assertEqual("package", text(execution, "m:phase"))
                self.assertEqual(["${slack-bot.images.goal}"],
                                 [goal.text for goal in execution.findall("m:goals/m:goal", NS)])
                config = execution.find("m:configuration", NS)
                self.assertEqual("${slack-bot." + role + ".base-image}",
                                 text(config, "m:from/m:image"))
                self.assertEqual("${slack-bot." + role + ".image}",
                                 text(config, "m:to/m:image"))
                self.assertEqual(main, text(config, "m:container/m:mainClass") or
                                 text(self.managed, "m:configuration/m:container/m:mainClass"))
                self.assertEqual(user, text(config, "m:container/m:user") or
                                 text(self.managed, "m:configuration/m:container/m:user"))
                self.assertEqual(image, text(self.profile, "m:properties/m:slack-bot." + role + ".image"))
                for key, suffix in (("tar", ".tar"), ("digest", ".digest"), ("imageId", ".id")):
                    output = text(config, "m:outputPaths/m:" + key)
                    self.assertEqual("${project.build.directory}/jib-" + role + "-image" + suffix, output)
                    self.assertNotIn(output, outputs)
                    outputs.add(output)
        self.assertEqual("dockerBuild", text(self.profile, "m:properties/m:slack-bot.images.goal"))
        agent = next(execution for execution in executions if text(execution, "m:id") == "agent-image")
        env = agent.find("m:configuration/m:container/m:environment", NS)
        for key, value in (("HOME", "/attempt/storage"), ("MAVEN_CONFIG", "/attempt/storage/.m2"),
                           ("MAVEN_OPTS", "-Duser.home=/attempt/storage")):
            self.assertEqual(value, text(env, "m:" + key))

    def test_all_bases_require_digests(self):
        enforcer = next(plugin for plugin in plugins(self.profile)
                        if text(plugin, "m:artifactId") == "maven-enforcer-plugin")
        execution = enforcer.find("m:executions/m:execution", NS)
        self.assertEqual("validate", text(execution, "m:phase"))
        self.assertEqual("enforce", text(execution, "m:goals/m:goal"))
        rules = execution.findall("m:configuration/m:rules/m:requireProperty", NS)
        self.assertEqual({"slack-bot." + role + ".base-image" for role in ROLES},
                         {text(rule, "m:property") for rule in rules})
        self.assertEqual(4, len(rules))
        for rule in rules:
            pattern = text(rule, "m:regex")
            self.assertIsNotNone(re.fullmatch(pattern, "registry/base@sha256:" + "a" * 64))
            for invalid in ("", "registry/base:25", "registry/base@sha256:abc", "base@sha256:" + "A" * 64):
                self.assertIsNone(re.fullmatch(pattern, invalid))
        for props in (self.pom.find("m:properties", NS), self.profile.find("m:properties", NS)):
            for prop in props:
                self.assertFalse(prop.tag.endswith(".base-image"), "No implicit base allowed")

    def test_no_default_image_build_or_other_profile_dependency(self):
        for plugin in plugins(self.pom):
            self.assertNotEqual("jib-maven-plugin", text(plugin, "m:artifactId"))
        self.assertIsNone(self.profile.find("m:activation", NS))
        for name, profile in self.profiles.items():
            if name != "image-all":
                for plugin in plugins(profile):
                    if text(plugin, "m:artifactId") == "jib-maven-plugin":
                        self.assertEqual([], plugin.findall("m:executions/m:execution", NS))
        self.assertNotIn("download", POM.read_text().lower())
        self.assertNotIn("dockerfile", POM.read_text().lower())

    def test_gateway_runtime_dependency_and_architecture(self):
        gateway = next(dep for dep in self.pom.findall("m:dependencies/m:dependency", NS)
                       if text(dep, "m:artifactId") == "bot-gateway")
        self.assertEqual("io.yupiik.ia.agent", text(gateway, "m:groupId"))
        self.assertEqual("${project.version}", text(gateway, "m:version"))
        self.assertIn(text(gateway, "m:scope"), (None, "compile", "runtime"))
        self.assertNotEqual("true", text(gateway, "m:optional"))
        for main, _, _ in ROLES.values():
            if main != CLI:
                source = ROOT / "extensions/bot-parent/bot-gateway/src/main/java" / (main.replace(".", "/") + ".java")
                self.assertIn("public static void main(", source.read_text())
        self.assertEqual("${slack-bot.image.architecture}", text(self.pom, "m:properties/m:bot.image.architecture"))
        self.assertEqual("${jib.arch}", text(self.pom, "m:properties/m:slack-bot.image.architecture"))
        parent = ET.parse(POM.parent.parent / "pom.xml").getroot()
        managed = parent.find("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
        self.assertEqual("jib-maven-plugin", text(managed, "m:artifactId"))
        self.assertEqual("${jib.version}", text(managed, "m:version"))
        self.assertEqual("3.5.2", text(parent, "m:properties/m:jib.version"))
        self.assertEqual("${bot.image.architecture}", text(managed,
                         "m:configuration/m:from/m:platforms/m:platform/m:architecture"))
        self.assertEqual("linux", text(managed, "m:configuration/m:from/m:platforms/m:platform/m:os"))

    def test_documented_single_command_covers_all_bases(self):
        doc = (ROOT / "documentation/src/main/minisite/content/slack-bot/deployment.adoc").read_text()
        command = re.search(r"mvn -pl extensions/bot-parent/slack-bot -am -Pimage-all.*?(?=\n----)", doc, re.S)
        self.assertIsNotNone(command)
        self.assertIn("-DskipTests package", command.group())
        for role in ROLES:
            self.assertIn("-Dslack-bot." + role + ".base-image=", command.group())
            self.assertIn("-Dslack-bot." + role + ".image=", command.group())
        self.assertIn("-Dslack-bot.image.architecture=amd64", command.group())
        self.assertIn("-Dslack-bot.images.goal=buildTar", doc)


if __name__ == "__main__":
    unittest.main()
