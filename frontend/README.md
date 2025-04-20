# OpenSearch Migrations Frontend

This is the frontend application for the OpenSearch Migrations project. It's built using Next.js and TypeScript, and integrated into the Gradle build system.

## Development

### Prerequisites

- Node.js 20.x or later
- npm

### Local Development

Run the development server:

```bash
cd frontend
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser to see the application.

### Gradle Integration

This Next.js project is integrated with Gradle through a custom plugin. You can use the following Gradle tasks:

- `./gradlew :frontend:buildFrontend` - Build the Next.js application
- `./gradlew :frontend:lintFrontend` - Run linters
- `./gradlew :frontend:testFrontend` - Run test cases with coverage
- `./gradlew :frontend:helpFrontend` - Show all of the scripts available for local development with npm

### Project Structure

- `src/app/` - Next.js app router files, React components, utility functions, and TypeScript types
- `public/` - Static assets

## Integration with the Main Build

This frontend project is integrated with the main OpenSearch Migrations build system. Running `./gradlew build` at the root level will also build the frontend application.

## Transformation Playground

The Transformation Playground is currently built/run as a separate React/Next app with the same structure as the main frontend. It can be built with:
`./gradlew :frontend:buildPlayground`

For development,

```sh
cd transformation-playground
npm run dev
```
