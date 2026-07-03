import os

def is_text_file(filepath):
    """
    Reads the first 1024 bytes of a file to determine if it is text or binary.
    """
    try:
        with open(filepath, 'rb') as f:
            chunk = f.read(1024)
        
        # Binary files (images, compiled code, PDFs) almost always contain null bytes.
        # If we find one, this is not a text file.
        if b'\x00' in chunk:
            return False
            
        # Verify it can actually be decoded as standard text
        chunk.decode('utf-8')
        return True
    except UnicodeDecodeError:
        # If it fails to decode as UTF-8 text, treat it as binary/unsupported and skip
        return False
    except Exception:
        # Skip if there's a permission error or it's unreadable
        return False

def pack_codebase(target_folder, output_filename):
    # Folders to skip to prevent uploading massive build files or hidden junk
    ignored_folders = {'build', '.git', '.idea', '.gradle', 'out', '__pycache__'}

    # Open the output file in write mode
    with open(output_filename, 'w', encoding='utf-8') as outfile:
        # Walk through the directory tree
        for root, dirs, files in os.walk(target_folder):
            # Modify the directories list in-place to skip ignored folders
            dirs[:] = [d for d in dirs if d not in ignored_folders and not d.startswith('.')]

            for file in files:
                filepath = os.path.join(root, file)
                
                # Check if the file is a text file before appending
                if is_text_file(filepath):
                    # Write the separator and the original file name
                    outfile.write("\n\n")
                    outfile.write("-" * 60 + "\n")
                    outfile.write(f"FILE: {filepath}\n")
                    outfile.write("-" * 60 + "\n\n")
                    
                    # Read the original file and write its contents
                    try:
                        # errors='replace' prevents crashing if a valid text file has a strange character
                        with open(filepath, 'r', encoding='utf-8', errors='replace') as infile:
                            outfile.write(infile.read())
                    except Exception as e:
                        outfile.write(f"[Error reading file: {e}]\n")

    print(f"✅ Success! All text-based files have been packed into: {output_filename}")


# ==========================================
# Run the script
# ==========================================

# Set this to your project folder path (use '.' for the current directory)
project_directory = "." 
output_txt_file = "packed_codebase.txt"

# Prevent the script from packing a previous output file into itself
if os.path.exists(output_txt_file):
    os.remove(output_txt_file)

pack_codebase(project_directory, output_txt_file)
