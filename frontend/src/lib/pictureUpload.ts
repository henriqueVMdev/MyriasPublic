import { uploadPicture } from "@/api/items";

/** Foto já hospedada no ML (`id`/`source`) + a URL usada só pra exibir no editor. */
export interface UploadedPicture {
  id?: string;
  source?: string;
  preview: string;
}

/**
 * Sobe as imagens escolhidas no input e devolve só as que o ML aceitou.
 *
 * O filtro de tipo mora aqui, não no chamador: o input aceita qualquer arquivo
 * arrastado, e a cópia do CloneListing tinha ficado sem a guarda que o BulkEdit
 * tinha — um PDF virava upload. Uma guarda só, no caminho por onde os dois passam.
 */
export async function uploadPictureFiles(files: FileList | File[]): Promise<UploadedPicture[]> {
  const uploaded: UploadedPicture[] = [];
  for (const file of Array.from(files)) {
    if (!file.type.startsWith("image/")) continue;
    const resp = await uploadPicture(file);
    if (resp.status !== 201 && resp.status !== 200) continue;
    const remoteId = resp.data.id as string | undefined;
    const remoteUrl = resp.data.variations?.[0]?.secure_url as string | undefined;
    if (!remoteId && !remoteUrl) {
      console.warn("Upload retornou sem id nem URL — ignorando", resp.data);
      continue;
    }
    uploaded.push({
      id: remoteId,
      source: remoteUrl || undefined,
      // Sem URL remota, o blob local segura o preview até o próximo carregamento.
      preview: remoteUrl || URL.createObjectURL(file),
    });
  }
  return uploaded;
}
