# Project Blueprint

## Overview

This document outlines the project structure, design, and features of the application. It serves as a single source of truth for the project's current state and future development plans.

## Style, Design, and Features

- **Project Structure:** The project follows a standard Flutter project structure, with the Android-specific code located in the `android` directory.
- **Refactoring:** The `session` package has been refactored into the `compose/common` package to improve code organization and clarity. The `dispatcher/strategy` package has been refactored into the `compose` package, with strategies split into `cn` and `en` subpackages.

## Current Plan

- **Refactor `dispatcher/strategy` package:** The `dispatcher/strategy` package has been moved to `compose` and all import statements have been updated.
- **Next Steps:** The next step is to continue with the development of the application.
