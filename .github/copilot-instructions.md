<!-- Use this file to provide workspace-specific custom instructions to Copilot. For more details, visit https://code.visualstudio.com/docs/copilot/copilot-customization#_use-a-githubcopilotinstructionsmd-file -->
- [x] Verify that the copilot-instructions.md file in the .github directory is created.

- [x] Clarify Project Requirements
	<!-- Ask for project type, language, and frameworks if not specified. Skip if already provided. -->

- [x] Scaffold the Project
	Project scaffolded with Kotlin Multiplatform and Compose for desktop, basic Hello World app created.

- [x] Customize the Project
	Implemented Olm/Megolm encryption using jOlm library (currently the only available Kotlin option).
	Note: jOlm is deprecated and superseded by vodozemac/matrix-sdk-crypto. Migration planned when Kotlin bindings become available.
	Room encryption detection, Megolm group encryption for sending messages, and UI indicators for encrypted rooms implemented.
	All compilation errors resolved and build successful.

- [x] Install Required Extensions
	Installed Kotlin extension for development.

- [x] Compile the Project
	Project compiled successfully with Gradle 8.5.

- [x] Create and Run Task
	Created and ran build task.

- [x] Launch the Project
	Application launched successfully in background. Ready for testing encryption workflow.

- [x] Implement Sync Token Persistence
	Sync token persistence implemented for incremental sync support. SessionData updated with syncToken field, all session operations updated accordingly.

- [x] Ensure Documentation is Complete
	README.md created, copilot-instructions.md updated.

- [x] Fix Encryption Issues
	Resolved end-to-end encryption problems with room key distribution. Implemented comprehensive Olm session establishment, device key queries, room key sharing with proper timing, and periodic sync for continuous to-device event processing. Application now successfully initializes Matrix SDK Crypto and processes encrypted messages.

- Work through each checklist item systematically.
- Keep communication concise and focused.
- Follow development best practices.
