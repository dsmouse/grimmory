import { ErrorHandler, Injectable } from '@angular/core';

@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
    handleError(error: unknown): void {
        const err = error instanceof Error ? error : undefined;
        const message = typeof error === 'string' ? error : err?.message ?? '';
        const name = err?.name ?? '';

        const chunkFailedMessage = /Loading chunk [\d]+ failed/i;
        const moduleFailedMessage = /Loading failed for the module/i;
        const dynamicImportFailedMessage = /Failed to fetch dynamically imported module|Importing a module script failed/i;

        if (
            chunkFailedMessage.test(message) ||
            moduleFailedMessage.test(message) ||
            dynamicImportFailedMessage.test(message) ||
            name === 'ChunkLoadError'
        ) {
            console.error('Chunk/Module load failed. Forcing reload to fetch latest version...', error);

            // Prevent infinite reload loop if the reload itself fails
            const now = Date.now();
            let shouldReload = true;

            try {
                const lastReloadValue = localStorage.getItem('last_chunk_error_reload');
                const lastReload = Number(lastReloadValue);
                shouldReload = !Number.isFinite(lastReload) || now - lastReload > 10000;

                if (shouldReload) {
                    localStorage.setItem('last_chunk_error_reload', now.toString());
                }
            } catch (storageError) {
                console.warn('Could not access reload guard storage; reloading anyway.', storageError);
            }

            if (shouldReload) {
                window.location.reload();
            } else {
                console.error('Chunk load failed multiple times in a short period. Stopped reloading to avoid loop.');
            }
        } else {
            console.error('An unexpected error occurred:', error);
        }
    }
}
