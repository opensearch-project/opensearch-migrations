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

### Container

This site is distributed via a small foot-print container running nginx.  To build and website on local port 8080 use run the following script.

```bash
./gradlew :frontend:buildDockerImage && \
    docker run -p 8080:80  migrations/website
```

### Gradle Integration

This Next.js project is integrated with Gradle through a custom plugin. You can use the following Gradle tasks:

- `./gradlew :frontend:buildFrontend` - Build the Next.js application
- `./gradlew :frontend:buildDockerImage` - Build the container that includes the website
- `./gradlew :frontend:lintFrontend` - Run linters
- `./gradlew :frontend:testFrontend` - Run test cases with coverage
- `./gradlew :frontend:helpFrontend` - Show all of the scripts available for local development with npm

### Project Structure

- `src/app/` - Next.js app router files, React components, utility functions, and TypeScript types
- `public/` - Static assets

## Integration with the Main Build

This frontend project is integrated with the main OpenSearch Migrations build system. Running `./gradlew build` at the root level will also build the frontend application.

## Connect to the Website

The website is deployed across all environments, but it is not directly accessible without additional steps to manage access control. The following sections describe how to connect to the website after a deployment.

### Local Kubernetes

Once the deployment has completed, run the following command. Then open [http://localhost:8080](http://localhost:8080) in your browser to access the website:

```bash
kubectl -n ma port-forward deploy/ma-migration-console 8080:80
```

### AWS Deployment

1. Open the AWS Console for the account where Migration Assistant is deployed.
2. Navigate to **EC2**, select or launch an instance to use as a forwarding proxy, and note its **region** and **instance ID**.
3. In the EC2 section, go to **Security Groups**, locate the group named `MigrationInfra-serviceSecurityGroup`, and add an **ingress rule** allowing traffic on port `80` from the selected EC2 instance.
4. Go to **ECS (Elastic Container Service)**, open the `migration` ECS cluster, then the `console` service. Click **Tasks**, and identify the running task (1 of 1). Click into the task and copy its **private IP address**.
5. From your local machine, run the following command to establish a port forwarding session from the EC2 instance to the web server running in the ECS container:
6. Once the session is established, open [http://localhost:8080](http://localhost:8080) in your browser.

```bash
REGION="us-east-1"
INSTANCE_ID="i-029db10a2a80b8486"
HOST_IP="10.212.142.146"

aws ssm start-session \
  --region "$REGION" \
  --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$HOST_IP\"],\"portNumber\":[\"80\"],\"localPortNumber\":[\"8080\"]}"
```
